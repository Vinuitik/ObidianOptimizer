package com.obsidian.obsidian.notes;

import com.obsidian.obsidian.chrono.ChronoService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class NoteLifecycleIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-it-vault");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                         VAULT::toString);
        r.add("spring.datasource.url",              postgres::getJdbcUrl);
        r.add("spring.datasource.username",         postgres::getUsername);
        r.add("spring.datasource.password",         postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @MockBean ChronoService chronoService;

    @Autowired FileRepository      fileRepo;
    @Autowired NoteIndexRepository noteIndex;
    @Autowired NoteLinkRepository  noteLinkRepo;

    @AfterEach
    void cleanAll() throws IOException {
        try (var stream = Files.walk(VAULT)) {
            stream.sorted(Comparator.reverseOrder())
                  .filter(p -> !p.equals(VAULT))
                  .forEach(p -> p.toFile().delete());
        }
        noteIndex.forceResync(List.<File>of());
    }

    private Path writeNote(String name, String body) throws IOException {
        Path f = VAULT.resolve(name);
        Files.writeString(f, body);
        return f;
    }

    private List<File> diskFiles() {
        return fileRepo.listMdPaths().stream()
            .map(Path::toFile).collect(Collectors.toList());
    }

    @Test
    void createNote_createsFileAndUpsertsToDB() throws IOException {
        String path = fileRepo.createNote(VAULT.toString(), "NewNote");

        assertThat(path).endsWith("NewNote.md");
        assertThat(Path.of(path)).exists();
        assertThat(noteIndex.getAllPaths()).contains(path);
    }

    @Test
    void createNote_missingFolder_throwsIOException() {
        assertThatThrownBy(() -> fileRepo.createNote("/no/such/folder", "Note"))
            .isInstanceOf(IOException.class);
    }

    @Test
    void createNote_duplicate_throwsIOException() throws IOException {
        fileRepo.createNote(VAULT.toString(), "Dup");
        assertThatThrownBy(() -> fileRepo.createNote(VAULT.toString(), "Dup"))
            .isInstanceOf(IOException.class);
    }

    @Test
    void syncWithDisk_insertsExternallyCreatedFile() throws IOException {
        writeNote("External.md", "---\nsr-due: 2025-01-01\nsr-interval: 3\nsr-ease: 200\n---\n");
        noteIndex.syncWithDisk(diskFiles());
        assertThat(noteIndex.getAllPaths()).anyMatch(p -> p.endsWith("External.md"));
    }

    @Test
    void syncWithDisk_deletesRemovedFile() throws IOException {
        String path = fileRepo.createNote(VAULT.toString(), "Gone");
        assertThat(noteIndex.getAllPaths()).contains(path);

        Files.delete(Path.of(path));
        noteIndex.syncWithDisk(List.<File>of());

        assertThat(noteIndex.getAllPaths()).doesNotContain(path);
    }

    @Test
    void syncWithDisk_unchangedFile_remainsInDB() throws IOException {
        String path = fileRepo.createNote(VAULT.toString(), "Stable");
        noteIndex.syncWithDisk(diskFiles());
        assertThat(noteIndex.getAllPaths()).contains(path);
    }

    @Test
    void patchNote_appendsLineAndUpdatesDB() throws IOException {
        String path = fileRepo.createNote(VAULT.toString(), "PatchMe");
        String original = Files.readString(Path.of(path));
        int lineCount = original.split("\n").length;

        fileRepo.patchNote(path, List.of(
            new FileRepository.PatchHunk(lineCount, 0, List.of("# Appended"))
        ));

        assertThat(Files.readString(Path.of(path))).contains("# Appended");
        assertThat(noteIndex.getAllPaths()).contains(path);
    }

    @Test
    void renameNote_movesFileAndUpdatesDB() throws IOException {
        String old = fileRepo.createNote(VAULT.toString(), "OldName");
        String new_ = fileRepo.renameNote(old, "NewName");

        assertThat(Path.of(new_)).exists();
        assertThat(Path.of(old)).doesNotExist();
        assertThat(noteIndex.getAllPaths()).contains(new_);
        assertThat(noteIndex.getAllPaths()).doesNotContain(old);
    }

    @Test
    void renameNote_rewritesWikiLinksInBacklinkSources() throws IOException {
        String targetPath = fileRepo.createNote(VAULT.toString(), "TargetNote");
        String linkBody = "---\nsr-due: 2025-01-01\nsr-interval: 3\nsr-ease: 200\n---\n\nSee [[TargetNote]].";
        Path linker = writeNote("Linker.md", linkBody);
        noteLinkRepo.updateLinks(linker.toString(), NoteLinkRepository.extractTargets(linkBody));

        fileRepo.renameNote(targetPath, "RenamedNote");

        String updated = Files.readString(linker);
        assertThat(updated).contains("[[RenamedNote]]");
        assertThat(updated).doesNotContain("[[TargetNote]]");
    }

    @Test
    void softDelete_movesToTrashAndRemovesFromDB() throws IOException {
        String path = fileRepo.createNote(VAULT.toString(), "ToTrash");
        fileRepo.softDeleteNote(path);

        assertThat(Path.of(path)).doesNotExist();
        assertThat(noteIndex.getAllPaths()).doesNotContain(path);

        Path trash = VAULT.resolve("_trash");
        assertThat(Files.list(trash).anyMatch(p -> p.getFileName().toString().startsWith("ToTrash")))
            .isTrue();
    }

    @Test
    void reviewQueue_returnsDueNotesAndExcludesFuture() throws IOException {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate nextWeek  = LocalDate.now().plusDays(7);
        writeNote("Overdue.md", "---\nsr-due: " + yesterday + "\nsr-interval: 3\nsr-ease: 200\n---\n");
        writeNote("Future.md",  "---\nsr-due: " + nextWeek  + "\nsr-interval: 3\nsr-ease: 200\n---\n");
        noteIndex.syncWithDisk(diskFiles());

        FileRepository.ReviewPage page = fileRepo.getReviewNotesPaged(0, 10);

        assertThat(page.notes()).anyMatch(p -> p.endsWith("Overdue.md"));
        assertThat(page.notes()).noneMatch(p -> p.endsWith("Future.md"));
    }

    @Test
    void reviewQueue_hasMoreFlag_limitPlusOneTrick() throws IOException {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        for (int i = 0; i < 3; i++) {
            writeNote("Due" + i + ".md", "---\nsr-due: " + yesterday + "\nsr-interval: 3\nsr-ease: 200\n---\n");
        }
        noteIndex.syncWithDisk(diskFiles());

        FileRepository.ReviewPage page = fileRepo.getReviewNotesPaged(0, 2);
        assertThat(page.notes()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void noteLinkIndex_storesLinksOnWrite() throws IOException {
        String body = "---\nsr-due: 2025-01-01\nsr-interval: 3\nsr-ease: 200\n---\n\n[[Alpha]] and [[Beta]].";
        String path = writeNote("Linker.md", body).toString();
        noteLinkRepo.updateLinks(path, NoteLinkRepository.extractTargets(body));

        assertThat(noteLinkRepo.findSourcesByTarget("Alpha")).contains(path);
        assertThat(noteLinkRepo.findSourcesByTarget("Beta")).contains(path);
    }

    @Test
    void noteLinkIndex_renameTarget_updatesEntries() throws IOException {
        String body = "---\nsr-due: 2025-01-01\nsr-interval: 3\nsr-ease: 200\n---\n\n[[OldTarget]].";
        String path = writeNote("Src.md", body).toString();
        noteLinkRepo.updateLinks(path, NoteLinkRepository.extractTargets(body));

        noteLinkRepo.renameTarget("OldTarget", "NewTarget");

        assertThat(noteLinkRepo.findSourcesByTarget("OldTarget")).isEmpty();
        assertThat(noteLinkRepo.findSourcesByTarget("NewTarget")).contains(path);
    }
}
