package com.cagedbird.droidv4l2

/** 图像处理工具类，提供 NV21 格式数据的旋转等操作。 */
object ImageUtils {

    /**
     * 将 NV21 格式的字节数组顺时针旋转 90 度。
     * @param data 原始 NV21 数据
     * @param width 原始宽度
     * @param height 原始高度
     * @return 旋转后的 NV21 数据
     */
    fun rotateNV21(data: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val out = ByteArray(data.size)
        var k = 0

        // 旋转 Y 分量 (1 byte per pixel)
        for (i in 0 until width) {
            for (j in height - 1 downTo 0) {
                out[k++] = data[j * width + i]
            }
        }

        // 旋转 UV 分量 (Interleaved V, U)
        // UV 块从 width * height 开始
        k = ySize
        for (i in 0 until width step 2) {
            for (j in (height / 2) - 1 downTo 0) {
                val offset = ySize + (j * width) + i
                out[k++] = data[offset] // V
                out[k++] = data[offset + 1] // U
            }
        }
        return out
    }
}
