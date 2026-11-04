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

    def test_table_lists_path_api_and_backends_per_row(self) -> None:
        combination = renderer_matrix.Combination("sodium", ("--renderer", "sodium"))
        outcome = renderer_matrix.Outcome(combination, "PASS", path="stencil", seconds=90.0,
                                          fields={"path": "stencil", "api": "OPENGL", "backends": "opengl/sodium/vanilla"})
        skipped = renderer_matrix.Outcome(renderer_matrix.Combination("vanilla+metal", (), "pass --metal"), "SKIPPED", detail="pass --metal")
        text = renderer_matrix.table([outcome, skipped])
        self.assertIn("| sodium | PASS | stencil | OPENGL | opengl/sodium/vanilla | 90 | - |", text)
        self.assertIn("| vanilla+metal | SKIPPED | - | - | - | - | pass --metal |", text)


if __name__ == "__main__":
    unittest.main()
