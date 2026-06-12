# Onboarding Flow Architecture

## Overview
The onboarding flow provides an interactive, guided tour for new users to demonstrate the core features of the Obsidian Optimizer application. It uses a "glow" or spotlight mechanism to sequentially highlight UI elements, relying on the application's actual flows to inform the steps.

## UX/UI Walkthrough
1. **Welcome Screen:** Quick context setting on application capabilities.
2. **Interactive Glow Tour:**
   - **Data Ingestion:** Highlights the sync/upload button to show how notes enter the system.
   - **Dashboard Info:** Highlights the processing statistics (charts) so the user knows where to monitor background AI work.
   - **Flashcards Review:** Focuses on the learning UI and explains the spaced repetition mechanism.
   - **Video/Resources:** Highlights the media queue manager for adding external links.

## Client State Management
- **Persistence:** Track `hasCompletedOnboarding` in the user's settings/preferences.
- **Step Tracking:** Store the `currentStep` internally using frontend state management (e.g., Zustand) to allow navigating forward, backward, or skipping entirely.
- **Component Refs:** Use React `refs` or designated DOM IDs/classes attached to the targeted UI elements to compute the glow overlay boundaries.

## Implementation Details
- **Library Selection:** Utilize an established guided tour library (e.g., `react-joyride` or `intro.js`) to parse target classes and handle the overlay z-index and glow effects.
- **Flow Alignment:** Map the tour steps to the main user journeys defined in `FLOWS.md`, ensuring the walkthrough logic accurately reflects how the system is meant to be used.