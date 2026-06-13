package com.obsidian.obsidian.ml;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure detection logic for the in-place ingest trigger — which resource embeds
 * still need synthesis. HTTP firing + vault-relative resolution are exercised
 * by the wider integration path, not here.
 */
class ResourceScanServiceTest {

    @Test
    void detectsVideoAudioAndPdfEmbeds() {
        String md = "# Note\n![[lecture.mp4]]\ntext\n![[talk.mp3]]\n![[paper.pdf]]\n";
        assertThat(ResourceScanService.embedsNeedingIngest(md))
            .containsExactly("lecture.mp4", "talk.mp3", "paper.pdf");
    }

    @Test
    void ignoresImageEmbedsAndPlainLinks() {
        String md = "![[diagram.png]] and [[Other Note]] and ![[clip.mp4]]";
        assertThat(ResourceScanService.embedsNeedingIngest(md))
            .containsExactly("clip.mp4");
    }

    @Test
    void skipsEmbedsThatAlreadyCarryTheirMarker() {
        String md = "![[lecture.mp4]]\n<!-- ingest:lecture.mp4 sha=ab12 -->\n"
                  + "synthesized\n<!-- /ingest:lecture.mp4 -->\n![[new.mp4]]\n";
        assertThat(ResourceScanService.embedsNeedingIngest(md))
            .containsExactly("new.mp4");
    }

    @Test
    void markerMatchByBasenameForPathfulEmbed() {
        String md = "![[resources/videos/lecture.mp4]]\n"
                  + "<!-- ingest:lecture.mp4 sha=x1 -->\nbody\n<!-- /ingest:lecture.mp4 -->\n";
        assertThat(ResourceScanService.embedsNeedingIngest(md)).isEmpty();
    }

    @Test
    void emptyOrNullContentYieldsNothing() {
        assertThat(ResourceScanService.embedsNeedingIngest("")).isEmpty();
        assertThat(ResourceScanService.embedsNeedingIngest(null)).isEmpty();
        assertThat(ResourceScanService.embedsNeedingIngest("no embeds at all")).isEmpty();
    }

    @Test
    void caseInsensitiveExtensions() {
        assertThat(ResourceScanService.embedsNeedingIngest("![[Talk.MP4]] ![[Doc.PDF]]"))
            .containsExactly("Talk.MP4", "Doc.PDF");
    }
}
