package com.obsidian.obsidian.inbox;

import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.vaults.ActiveVault;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InboxFolderMigrationTest {

    private static String note(String cid, String title) {
        return "---\ncapture-id: " + cid + "\ningest-source-title: " + title + "\n---\n\nbody";
    }

    @Test
    void renamesHashFoldersStampsNamedFoldersAndIsIdempotent() throws Exception {
        Path vault = Files.createTempDirectory("vault");
        Path inbox = Files.createDirectories(vault.resolve("_inbox"));
        Files.createDirectories(inbox.resolve("abc123/Ch-1"));
        Files.writeString(inbox.resolve("abc123/n1.md"), note("abc123", "My: Talk"));
        Files.writeString(inbox.resolve("abc123/Ch-1/n2.md"), note("abc123", "My: Talk"));
        // second capture, same title -> " 2"
        Files.createDirectories(inbox.resolve("def456"));
        Files.writeString(inbox.resolve("def456/n3.md"), note("def456", "My Talk"));
        // MCP folder without capture-id; loose root note untouched
        Files.createDirectories(inbox.resolve("Mobile PWA Notes"));
        Files.writeString(inbox.resolve("Mobile PWA Notes/a.md"), "---\nfoo: 1\n---\nA");
        Files.writeString(inbox.resolve("Mobile PWA Notes/b.md"), "plain");
        Files.writeString(inbox.resolve("loose.md"), "loose");

        FileRepository repo = mock(FileRepository.class);
        when(repo.moveNote(anyString(), anyString())).thenAnswer(inv -> {
            Path src = Path.of(inv.getArgument(0, String.class));
            Path dst = Path.of(inv.getArgument(1, String.class)).resolve(src.getFileName());
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
            return dst.toString();
        });
        doAnswer(inv -> {
            Files.writeString(Path.of(inv.getArgument(1, String.class)), inv.getArgument(2, String.class));
            return null;
        }).when(repo).updateNote(anyLong(), anyString(), anyString());

        InboxFolderMigration m = new InboxFolderMigration(repo, ActiveVault.forTest(1L, vault));
        var dry = m.run(true);
        assertThat(dry.renamed()).hasSize(2);
        assertThat(Files.exists(inbox.resolve("abc123"))).isTrue();   // dry run touched nothing

        var rep = m.run(false);
        assertThat(rep.renamed()).containsExactly("abc123 -> My Talk", "def456 -> My Talk 2");
        assertThat(Files.exists(inbox.resolve("abc123"))).isFalse();
        assertThat(inbox.resolve("My Talk/n1.md")).exists();
        assertThat(inbox.resolve("My Talk/Ch-1/n2.md")).exists();
        assertThat(inbox.resolve("My Talk 2/n3.md")).exists();

        String cid = InboxFolderMigration.mcpCaptureId(1L, "Mobile PWA Notes");
        assertThat(rep.stamped()).hasSize(2);
        assertThat(Files.readString(inbox.resolve("Mobile PWA Notes/a.md"))).contains("capture-id: " + cid);
        assertThat(Files.readString(inbox.resolve("Mobile PWA Notes/b.md"))).startsWith("---\ncapture-id: " + cid);
        assertThat(Files.readString(inbox.resolve("loose.md"))).isEqualTo("loose");

        var again = m.run(false);   // idempotent
        assertThat(again.renamed()).isEmpty();
        assertThat(again.stamped()).isEmpty();
    }
}
