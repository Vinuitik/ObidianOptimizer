package com.obsidian.obsidian;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Context load is verified by NoteLifecycleIT and ChronoServiceIT (which use Testcontainers).
// Disabled here to avoid requiring a live DB in the unit-test phase.
@Disabled
@SpringBootTest
class ObsidianApplicationTests {

	@Test
	void contextLoads() {
	}

}
