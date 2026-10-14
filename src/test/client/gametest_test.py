import unittest

import gametest


class NativeFrameTest(unittest.TestCase):
    def test_native_frame_accepts_only_native_size_without_a_declared_resize(self) -> None:
        self.assertTrue(gametest.native_frame({"frameWidth": 1920, "frameHeight": 1080}))
        self.assertTrue(gametest.native_frame({"frameWidth": 1920, "frameHeight": 1080, "declaredFrameSize": None}))
        self.assertFalse(gametest.native_frame({"frameWidth": 1440, "frameHeight": 810}))
        self.assertFalse(gametest.native_frame({"frameWidth": None, "frameHeight": None}))

    def test_native_frame_accepts_the_declared_test_size_and_native_size_during_a_resize(self) -> None:
        self.assertTrue(gametest.native_frame({"frameWidth": 1440, "frameHeight": 810, "declaredFrameSize": "1440x810"}))
        self.assertTrue(gametest.native_frame({"frameWidth": 1920, "frameHeight": 1080, "declaredFrameSize": "1440x810"}))
        self.assertFalse(gametest.native_frame({"frameWidth": 1280, "frameHeight": 720, "declaredFrameSize": "1440x810"}))
        self.assertFalse(gametest.native_frame({"frameWidth": 1440, "frameHeight": 810, "declaredFrameSize": "1440"}))


if __name__ == "__main__":
    unittest.main()
