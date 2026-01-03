package com.cagedbird.droidv4l2

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class ImageUtilsTest {

    @Test
    fun `test rotateNV21 90 degrees clockwise`() {
        // 2x2 Image
        // Y Plane:
        // [1, 2]
        // [3, 4]
        // UV Plane (interleaved V, U):
        // [5, 6] (V1, U1 for all pixels in this 2x2 since UV is subsampled)
        // Note: For a 2x2 NV21, Y is 4 bytes, UV is 2 bytes (1 V, 1 U)

        val width = 2
        val height = 2
        val input =
                byteArrayOf(
                        1,
                        2, // Y
                        3,
                        4,
                        5,
                        6 // V, U
                )

        // Expected 90 degree clockwise:
        // Y becomes:
        // [3, 1]
        // [4, 2]
        // UV block for 2x2: Since it's only one sample pair per 2x2 block, it remains [5, 6]
        // but let's test a 2x4 or something more complex if needed.
        // For 2x2, the loop logic for UV will run once.

        val expected = byteArrayOf(3, 1, 4, 2, 5, 6)

        val result = ImageUtils.rotateNV21(input, width, height)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `test rotateNV21 complex 4x2`() {
        // 4 columns, 2 rows
        // Y:
        // [1, 2, 3, 4]
        // [5, 6, 7, 8]
        // UV (2x1 pairs):
        // [V1, U1, V2, U2]

        val width = 4
        val height = 2
        val input =
                byteArrayOf(
                        1,
                        2,
                        3,
                        4,
                        5,
                        6,
                        7,
                        8,
                        9,
                        10,
                        11,
                        12 // UV
                )

        // Rotated 90 Deg CW (becomes 2x4):
        // Y:
        // [5, 1]
        // [6, 2]
        // [7, 3]
        // [8, 4]
        // UV logic:
        // step 2, height 2.
        // x=0: y=0: offset=8 + 0*4 + 0 = 8. out[k++]=9, 10
        // x=2: y=0: offset=8 + 0*4 + 2 = 10. out[k++]=11, 12

        val expected = byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4, 9, 10, 11, 12)

        val result = ImageUtils.rotateNV21(input, width, height)
        assertArrayEquals(expected, result)
    }
}
