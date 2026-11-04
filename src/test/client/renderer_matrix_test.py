import argparse
import unittest
from pathlib import Path

import renderer_matrix


def namespace(**overrides: object) -> argparse.Namespace:
    values: dict[str, object] = {"metal": None, "distant_horizons": Path("/tmp/dh.jar"), "shader_pack": None, "only": None,
                                 "minecraft_version": "26.3"}
    values.update(overrides)
    return argparse.Namespace(**values)


class CombinationsTest(unittest.TestCase):
    def test_metal_and_dh_pack_rows_are_skipped_with_their_requirement_when_their_inputs_are_missing(self) -> None:
        rows = {combination.name: combination for combination in renderer_matrix.combinations(namespace())}
        self.assertEqual((), rows["vanilla+metal"].arguments)
        self.assertIn("--metal", rows["vanilla+metal"].requirement)
        self.assertIn("--shader-pack", rows["sodium+iris+pack+dh"].requirement)
        self.assertEqual("", rows["sodium+dh"].requirement)
        self.assertEqual(("--renderer", "sodium", "--distant-horizons", str(Path("/tmp/dh.jar").resolve())), rows["sodium+dh"].arguments)
        self.assertEqual(("--renderer", "vanilla", "--graphics-backend", "vulkan"), rows["vanilla+vulkan"].arguments)

    def test_only_keeps_the_named_rows_and_rejects_unknown_names(self) -> None:
        names = [combination.name for combination in renderer_matrix.combinations(namespace(only="sodium,vanilla+vulkan"))]
        self.assertEqual(["sodium", "vanilla+vulkan"], names)
        with self.assertRaises(ValueError):
            renderer_matrix.combinations(namespace(only="optifine"))

    def test_fields_keep_the_reasons_whole_after_the_single_word_values(self) -> None:
        parsed = renderer_matrix.fields(" path=mesh decided=mesh device=metal backends=metal/metal_shaders/metal_shaders"
                                        " reasons=device Metal has no shader clip planes; terrain owner metal_shaders cannot be clipped")
        self.assertEqual("mesh", parsed["path"])
        self.assertEqual("metal/metal_shaders/metal_shaders", parsed["backends"])
        self.assertEqual("device Metal has no shader clip planes; terrain owner metal_shaders cannot be clipped", parsed["reasons"])
        self.assertEqual({"path": "stencil", "reasons": "-"}, renderer_matrix.fields(" path=stencil reasons=-"))

    def test_table_lists_path_device_backends_and_reasons_per_row(self) -> None:
        combination = renderer_matrix.Combination("sodium", ("--renderer", "sodium"))
        outcome = renderer_matrix.Outcome(combination, "PASS", path="stencil", seconds=90.0,
                                          fields={"path": "stencil", "device": "opengl", "backends": "opengl/sodium/vanilla", "reasons": "-"})
        skipped = renderer_matrix.Outcome(renderer_matrix.Combination("vanilla+metal", (), "pass --metal"), "SKIPPED", detail="pass --metal")
        text = renderer_matrix.table([outcome, skipped])
        self.assertIn("| sodium | PASS | stencil | opengl | opengl/sodium/vanilla | - | 90 | - |", text)
        self.assertIn("| vanilla+metal | SKIPPED | - | - | - | - | - | pass --metal |", text)


if __name__ == "__main__":
    unittest.main()
