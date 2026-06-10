package com.obsidian.obsidian.media;

import com.obsidian.obsidian.settings.SettingsRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaControllerTest {

    @Mock SettingsRepository settingsRepo;
    @TempDir Path vault;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(settingsRepo.getVaultPath()).thenReturn(vault.toString());
        mvc = MockMvcBuilders.standaloneSetup(new MediaController(settingsRepo)).build();
    }

    // ── subdirFor ─────────────────────────────────────────────────────────────

    @Test void subdirFor_png()  { assertThat(MediaController.subdirFor("x.png")).isEqualTo("images"); }
    @Test void subdirFor_jpg()  { assertThat(MediaController.subdirFor("x.jpg")).isEqualTo("images"); }
    @Test void subdirFor_jpeg() { assertThat(MediaController.subdirFor("x.jpeg")).isEqualTo("images"); }
    @Test void subdirFor_gif()  { assertThat(MediaController.subdirFor("x.gif")).isEqualTo("images"); }
    @Test void subdirFor_webp() { assertThat(MediaController.subdirFor("x.webp")).isEqualTo("images"); }
    @Test void subdirFor_svg()  { assertThat(MediaController.subdirFor("x.svg")).isEqualTo("images"); }
    @Test void subdirFor_mp4()  { assertThat(MediaController.subdirFor("x.mp4")).isEqualTo("videos"); }
    @Test void subdirFor_mov()  { assertThat(MediaController.subdirFor("x.mov")).isEqualTo("videos"); }
    @Test void subdirFor_mkv()  { assertThat(MediaController.subdirFor("x.mkv")).isEqualTo("videos"); }
    @Test void subdirFor_webm() { assertThat(MediaController.subdirFor("x.webm")).isEqualTo("videos"); }
    @Test void subdirFor_avi()  { assertThat(MediaController.subdirFor("x.avi")).isEqualTo("videos"); }
    @Test void subdirFor_mp3()  { assertThat(MediaController.subdirFor("x.mp3")).isEqualTo("audio"); }
    @Test void subdirFor_wav()  { assertThat(MediaController.subdirFor("x.wav")).isEqualTo("audio"); }
    @Test void subdirFor_ogg()  { assertThat(MediaController.subdirFor("x.ogg")).isEqualTo("audio"); }
    @Test void subdirFor_m4a()  { assertThat(MediaController.subdirFor("x.m4a")).isEqualTo("audio"); }
    @Test void subdirFor_flac() { assertThat(MediaController.subdirFor("x.flac")).isEqualTo("audio"); }
    @Test void subdirFor_pdf()  { assertThat(MediaController.subdirFor("x.pdf")).isEqualTo("pdf"); }
    @Test void subdirFor_zip()  { assertThat(MediaController.subdirFor("x.zip")).isEqualTo("files"); }
    @Test void subdirFor_noExt(){ assertThat(MediaController.subdirFor("file")).isEqualTo("files"); }
    @Test void subdirFor_upperCase() { assertThat(MediaController.subdirFor("x.PNG")).isEqualTo("images"); }

    // ── mimeFor ───────────────────────────────────────────────────────────────

    @Test void mimeFor_png()  { assertThat(MediaController.mimeFor("x.png")).isEqualTo("image/png"); }
    @Test void mimeFor_mp4()  { assertThat(MediaController.mimeFor("x.mp4")).isEqualTo("video/mp4"); }
    @Test void mimeFor_mp3()  { assertThat(MediaController.mimeFor("x.mp3")).isEqualTo("audio/mpeg"); }
    @Test void mimeFor_pdf()  { assertThat(MediaController.mimeFor("x.pdf")).isEqualTo("application/pdf"); }
    @Test void mimeFor_svg()  { assertThat(MediaController.mimeFor("x.svg")).isEqualTo("image/svg+xml"); }
    @Test void mimeFor_unknown() { assertThat(MediaController.mimeFor("x.bin")).isEqualTo("application/octet-stream"); }
    @Test void mimeFor_noExt()   { assertThat(MediaController.mimeFor("file")).isEqualTo("application/octet-stream"); }

    // ── POST /upload ──────────────────────────────────────────────────────────

    @Test
    void upload_png_savedToImagesSubdir() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "photo.png", "image/png", "data".getBytes());
        mvc.perform(multipart("/upload").file(f).param("filename", "photo-1234abc.png"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.filename").value("photo-1234abc.png"))
           .andExpect(jsonPath("$.url").value("/api/images/photo-1234abc.png"));
        assertThat(vault.resolve("resources/images/photo-1234abc.png")).exists();
    }

    @Test
    void upload_svg_savedToImagesSubdir() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "icon.svg", "image/svg+xml", "<svg/>".getBytes());
        mvc.perform(multipart("/upload").file(f).param("filename", "icon-1234abc.svg"))
           .andExpect(status().isOk());
        assertThat(vault.resolve("resources/images/icon-1234abc.svg")).exists();
    }

    @Test
    void upload_mp4_savedToVideosSubdir() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "clip.mp4", "video/mp4", "data".getBytes());
        mvc.perform(multipart("/upload").file(f).param("filename", "clip-1234abc.mp4"))
           .andExpect(status().isOk());
        assertThat(vault.resolve("resources/videos/clip-1234abc.mp4")).exists();
    }

    @Test
    void upload_mp3_savedToAudioSubdir() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "sound.mp3", "audio/mpeg", "data".getBytes());
        mvc.perform(multipart("/upload").file(f).param("filename", "sound-1234abc.mp3"))
           .andExpect(status().isOk());
        assertThat(vault.resolve("resources/audio/sound-1234abc.mp3")).exists();
    }

    @Test
    void upload_pdf_savedToPdfSubdir() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "doc.pdf", "application/pdf", "data".getBytes());
        mvc.perform(multipart("/upload").file(f).param("filename", "doc-1234abc.pdf"))
           .andExpect(status().isOk());
        assertThat(vault.resolve("resources/pdf/doc-1234abc.pdf")).exists();
    }

    @Test
    void upload_pathTraversalFilename_returns400() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "evil.png", "image/png", "data".getBytes());
        mvc.perform(multipart("/upload").file(f).param("filename", "../../../etc/evil.png"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void upload_backslashInFilename_returns400() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "x.png", "image/png", "data".getBytes());
        mvc.perform(multipart("/upload").file(f).param("filename", "foo\\bar.png"))
           .andExpect(status().isBadRequest());
    }

    // ── GET /images/{filename} ────────────────────────────────────────────────

    @Test
    void getImage_foundInImagesSubdir_returns200WithCorrectMime() throws Exception {
        Path dir = vault.resolve("resources/images");
        Files.createDirectories(dir);
        Files.write(dir.resolve("test.png"), new byte[]{1, 2, 3});

        mvc.perform(get("/images/test.png"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", containsString("image/png")));
    }

    @Test
    void getImage_foundInVideosSubdir_returns200WithCorrectMime() throws Exception {
        Path dir = vault.resolve("resources/videos");
        Files.createDirectories(dir);
        Files.write(dir.resolve("clip.mp4"), new byte[]{1, 2, 3});

        mvc.perform(get("/images/clip.mp4"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", containsString("video/mp4")));
    }

    @Test
    void getImage_foundInAudioSubdir_returns200() throws Exception {
        Path dir = vault.resolve("resources/audio");
        Files.createDirectories(dir);
        Files.write(dir.resolve("track.mp3"), new byte[]{1, 2, 3});

        mvc.perform(get("/images/track.mp3"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", containsString("audio/mpeg")));
    }

    @Test
    void getImage_notFoundInAnySubdir_returns404() throws Exception {
        mvc.perform(get("/images/missing.png"))
           .andExpect(status().isNotFound());
    }

    @Test
    void getImage_uploadedThenServed_roundTrip() throws Exception {
        byte[] content = "fake image bytes".getBytes();
        MockMultipartFile f = new MockMultipartFile("file", "img.png", "image/png", content);
        mvc.perform(multipart("/upload").file(f).param("filename", "img-roundtrip.png"))
           .andExpect(status().isOk());

        mvc.perform(get("/images/img-roundtrip.png"))
           .andExpect(status().isOk())
           .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(content));
    }

    @Test
    void upload_createsSubdirIfMissing() throws Exception {
        assertThat(vault.resolve("resources/videos")).doesNotExist();
        MockMultipartFile f = new MockMultipartFile("file", "v.mp4", "video/mp4", "data".getBytes());
        mvc.perform(multipart("/upload").file(f).param("filename", "v-abc.mp4"))
           .andExpect(status().isOk());
        assertThat(vault.resolve("resources/videos")).isDirectory();
    }
}
