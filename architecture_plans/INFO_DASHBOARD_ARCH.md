# Information Dashboard Architecture

## Overview
A dedicated information and statistics page providing transparency into the asynchronous AI background processing. It utilizes three primary charts to show the user exactly what is ready to study, what is currently being embedded, and what remains in the queue.

## Core Charts & Metrics

### 1. Vector Embedding Progress
- **Purpose:** Shows how many notes/images have been fully chunked and embedded vs. what is still pending.
- **Data Points:** Count of Processed Chunks/Notes vs. Total Expected Chunks/Notes.
- **Recommended Chart:** Pie Chart or Circular gauge.

### 2. Flashcard Generation Coverage
- **Purpose:** Illustrates the progress of automated flashcard creation.
- **Data Points:** Count of notes that have corresponding flashcards generated vs. Total notes eligible for flashcards.
- **Recommended Chart:** Progress Bar or Bar Chart.

### 3. Video & External Resource Queue
- **Purpose:** Tracks the status of the media ingestion agent (summarizing, transcribing videos).
- **Data Points:** Processed Resources (Ready to use) vs. Resources in Queue (Waiting).
- **Recommended Chart:** Stacked Bar Chart or Doughnut Chart.

## Backend Integration
- **Aggregated Endpoints:** The backend (Python/Java services) will expose a `/api/stats` or `/api/processing-status` endpoint returning lightweight count aggregates rather than fetching the entire dataset.
- **Real-time Updates:** Given processing can take time, the frontend should fetch data using either short-polling (every X seconds) or Server-Sent Events (SSE) to update the charts automatically.

## Frontend Implementation
- **Location:** New route (e.g., `/dashboard` or `/info`) inside the frontend application.
- **Styling/Libraries:** Integrate a charting library like `Recharts` or `Chart.js` for clean, responsive data visualization matching the `DESIGN.md` tokens.