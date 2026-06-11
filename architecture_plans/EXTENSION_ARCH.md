# Browser Extension Architecture

## Overview
A Manifest V3 browser extension for Chrome and Firefox that injects a real-time note-taking UI natively onto any webpage. Designed to reduce friction between consuming information and storing it.

## Tech Stack
*   **Build Tool**: Vite + `@crxjs/vite-plugin` (Compiles React directly to browser extension formats).
*   **UI Framework**: React (Reusing existing components: `NewNoteForm`, `FolderTree`, `MilkdownEditor`).
*   **Isolation**: Shadow DOM (Ensures host webpage CSS doesn't break our UI).

## Core Architecture

### 1. The Content Script (The UI)
This script is injected into every webpage the user visits. 
*   It mounts a React application inside a custom HTML element (`<obsidian-optimizer-overlay>`).
*   A **Shadow DOM** is used to encapsulate the React components so that global styles from the current website (like Wikipedia or YouTube) do not bleed into our note-taking popup.
*   Provides a floating action button that expands into the full `NewNoteForm` UI.

### 2. The Background Script (Service Worker)
Runs invisibly in the browser background.
*   **API & CORS**: Content scripts are subject to the same CORS rules as the host webpage. To bypass this, the content script sends a message to the Background Script, which then securely communicates with the Spring Boot API (`http://localhost:8080` or production URL).
*   **State & Auth**: Holds JWT tokens or session state. Handles the actual `POST` requests to save notes.

### 3. Component Decoupling
*   To reuse components from `frontend/src/`, the API communication layer needs to be environment-aware.
*   When running as an extension, components will dispatch messages (`chrome.runtime.sendMessage`) to the Service Worker instead of making direct `fetch` calls, or the base URL must be absolute and the Background Script handles the proxying.

## Development Flow
1. Scaffold an `extension/` folder using Vite.
2. Link the shared UI components.
3. Build the Shadow DOM injector.
4. Wire up the messaging between the overlay UI and the Background Service Worker.
5. Send payloads to the main Backend API.