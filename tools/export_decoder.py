"""Export an official EnCodec RVQ + decoder as an ExecuTorch program."""

from __future__ import annotations

import argparse
import os
import time
from pathlib import Path

import torch
from encodec import EncodecModel
from torch import nn


class EncodecMobileDecoder(nn.Module):
    """Fixed-shape, bitrate-flexible mobile decoder.

    `codes` is [16, 1, 150]. `active` masks unused residual codebooks, which
    lets one exported model handle 3/6/12/24 kbps without duplicating weights.
    """

    def __init__(self, model: EncodecModel):
        super().__init__()
        self.layers = model.quantizer.vq.layers
        self.decoder = model.decoder
        self.dimension = model.quantizer.dimension

    def forward(self, codes: torch.Tensor, active: torch.Tensor) -> torch.Tensor:
        quantized = torch.zeros(
            (1, self.dimension, codes.shape[-1]),
            dtype=torch.float32,
            device=codes.device,
        )
        for index, layer in enumerate(self.layers):
            quantized = quantized + layer.decode(codes[index]) * active[index]
        return self.decoder(quantized)


def export(output: Path, variant: str) -> None:
    import executorch
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.exir import to_edge_transform_and_lower
    from executorch.runtime import Runtime

    # The wheel bundles flatc but does not add it to PATH on every platform.
    bundled_bin = Path(next(iter(executorch.__path__))).resolve() / "data" / "bin"
    os.environ["PATH"] = f"{bundled_bin}{os.pathsep}{os.environ.get('PATH', '')}"

    torch.set_grad_enabled(False)
    if variant == "48khz":
        official = EncodecModel.encodec_model_48khz().eval()
        model_time_steps = 150
        expected_codebooks = 16
    else:
        official = EncodecModel.encodec_model_24khz().eval()
        # Four seconds of new mono codes plus eight latent history steps.
        model_time_steps = 308
        expected_codebooks = 32
    wrapper = EncodecMobileDecoder(official).eval()

    max_codebooks = official.quantizer.n_q
    assert max_codebooks == expected_codebooks, max_codebooks
    torch.manual_seed(7)
    codes = torch.randint(0, 1024, (max_codebooks, 1, model_time_steps), dtype=torch.int64)
    active = torch.tensor([1.0] * 8 + [0.0] * 8, dtype=torch.float32)
    if max_codebooks > 16:
        active = torch.cat([active, torch.zeros(max_codebooks - 16)])

    eager_pcm = wrapper(codes, active)
    exported = torch.export.export(wrapper, (codes, active), strict=True)
    program = to_edge_transform_and_lower(
        exported,
        partitioner=[XnnpackPartitioner()],
    ).to_executorch()

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(program.buffer)

    started = time.perf_counter()
    runtime_pcm = (
        Runtime.get()
        .load_program(output)
        .load_method("forward")
        .execute([codes, active])[0]
    )
    runtime_seconds = time.perf_counter() - started
    difference = (eager_pcm - runtime_pcm).abs()
    max_error = difference.max().item()
    mean_error = difference.mean().item()
    if max_error > 0.01:
        raise RuntimeError(f"Export verification failed: max error {max_error:.6f}")

    print(f"wrote {output} ({output.stat().st_size / 1024 / 1024:.1f} MiB)")
    print(f"verified output shape: {tuple(runtime_pcm.shape)}")
    print(f"max/mean absolute error: {max_error:.6f} / {mean_error:.6f}")
    print(f"desktop cold load + one fixed decoder window: {runtime_seconds:.3f} s")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
    )
    parser.add_argument("--variant", choices=("24khz", "48khz"), default="48khz")
    args = parser.parse_args()
    output = args.output or Path(f"app/src/main/assets/encodec_{args.variant}_decoder.pte")
    export(output, args.variant)
