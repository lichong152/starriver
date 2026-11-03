package app.xingchuan.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 壁纸来源的序列化必须能在重启后原样还原，否则用户选的图片/视频会丢。 */
class WallpaperSpecTest {

    @Test
    fun presetRoundTrip() {
        val spec = WallpaperSpec.Preset("river")
        assertEquals(spec, WallpaperSpec.decode(spec.encode()))
    }

    @Test
    fun imageRoundTrip() {
        val spec = WallpaperSpec.Picture("content://media/external/images/media/42")
        assertEquals(spec, WallpaperSpec.decode(spec.encode()))
    }

    @Test
    fun videoRoundTrip() {
        val spec = WallpaperSpec.Video("content://media/external/video/media/7")
        assertEquals(spec, WallpaperSpec.decode(spec.encode()))
    }

    @Test
    fun uriWithColonSurvives() {
        // content:// 本身带冒号，切分必须只按第一个冒号，否则 uri 会被截断
        val decoded = WallpaperSpec.decode("image:content://media/external/images/media/9")
        assertTrue(decoded is WallpaperSpec.Picture)
        assertEquals(
            "content://media/external/images/media/9",
            (decoded as WallpaperSpec.Picture).uri,
        )
    }

    @Test
    fun garbageFallsBackToDefault() {
        assertEquals(WallpaperSpec.Default, WallpaperSpec.decode(null))
        assertEquals(WallpaperSpec.Default, WallpaperSpec.decode(""))
        assertEquals(WallpaperSpec.Default, WallpaperSpec.decode("nonsense"))
        assertEquals(WallpaperSpec.Default, WallpaperSpec.decode("image:"))
        assertEquals(WallpaperSpec.Default, WallpaperSpec.decode("weird:value"))
    }
}
