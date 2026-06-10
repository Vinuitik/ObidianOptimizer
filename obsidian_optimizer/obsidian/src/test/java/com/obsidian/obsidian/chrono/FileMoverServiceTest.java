package com.obsidian.obsidian.chrono;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileMoverServiceTest {

    @TempDir Path vault;
    FileMoverService service = new FileMoverService();

    private void createFile(String name) throws IOException {
        Files.writeString(vault.resolve(name), "data");
    }

    @Test
    void movesPngToImagesDir() throws IOException {
        createFile("photo.png");
        service.run(vault.toString());
        assertThat(vault.resolve("resources/images/photo.png")).exists();
        assertThat(vault.resolve("photo.png")).doesNotExist();
    }

    @Test
    void movesJpgToImagesDir() throws IOException {
        createFile("image.jpg");
        service.run(vault.toString());
        assertThat(vault.resolve("resources/images/image.jpg")).exists();
    }

    @Test
    void movesJpegToImagesDir() throws IOException {
        createFile("photo.jpeg");
        service.run(vault.toString());
        assertThat(vault.resolve("resources/images/photo.jpeg")).exists();
    }

    @Test
    void movesGifToImagesDir() throws IOException {
        createFile("anim.gif");
        service.run(vault.toString());
        assertThat(vault.resolve("resources/images/anim.gif")).exists();
    }

    @Test
    void movesWebpToImagesDir() throws IOException {
        createFile("banner.webp");
        service.run(vault.toString());
        assertThat(vault.resolve("resources/images/banner.webp")).exists();
    }

    @Test
    void movesPdfToPdfDir() throws IOException {
        createFile("doc.pdf");
        service.run(vault.toString());
        assertThat(vault.resolve("resources/pdf/doc.pdf")).exists();
        assertThat(vault.resolve("doc.pdf")).doesNotExist();
    }

    @Test
    void movesMp4ToVideosDir() throws IOException {
        createFile("clip.mp4");
        service.run(vault.toString());
        assertThat(vault.resolve("resources/videos/clip.mp4")).exists();
    }

    @Test
    void movesMovToVideosDir() throws IOException {
        createFile("film.mov");
        service.run(vault.toString());
        assertThat(vault.resolve("resources/videos/film.mov")).exists();
    }

    @Test
    void movesMkvToVideosDir() throws IOException {
        createFile("movie.mkv");
        service.run(vault.toString());
        assertThat(vault.resolve("resources/videos/movie.mkv")).exists();
    }

    @Test
    void markdownFileNotMoved() throws IOException {
        createFile("note.md");
        service.run(vault.toString());
        assertThat(vault.resolve("note.md")).exists();
    }

    @Test
    void unknownExtensionNotMoved() throws IOException {
        createFile("data.csv");
        service.run(vault.toString());
        assertThat(vault.resolve("data.csv")).exists();
    }

    @Test
    void fileWithNoExtensionNotMoved() throws IOException {
        createFile("README");
        service.run(vault.toString());
        assertThat(vault.resolve("README")).exists();
    }

    @Test
    void filesInSubdirNotMoved() throws IOException {
        Path sub = vault.resolve("subfolder");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("nested.png"), "data");
        service.run(vault.toString());
        assertThat(sub.resolve("nested.png")).exists();
        assertThat(vault.resolve("resources/images/nested.png")).doesNotExist();
    }

    @Test
    void createsDirsIfMissing() throws IOException {
        createFile("pic.png");
        service.run(vault.toString());
        assertThat(vault.resolve("resources/images")).isDirectory();
        assertThat(vault.resolve("resources/pdf")).isDirectory();
        assertThat(vault.resolve("resources/videos")).isDirectory();
    }

    @Test
    void returnsCountOfMovedFiles() throws IOException {
        createFile("a.png");
        createFile("b.pdf");
        createFile("c.mp4");
        int moved = service.run(vault.toString());
        assertThat(moved).isEqualTo(3);
    }

    @Test
    void returnsZeroWhenNothingToMove() throws IOException {
        createFile("note.md");
        int moved = service.run(vault.toString());
        assertThat(moved).isEqualTo(0);
    }
}
