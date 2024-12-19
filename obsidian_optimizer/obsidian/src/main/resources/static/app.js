import { renderMarkdown } from './renderMarkdown.js';
import { buildFolderTree } from './folderStructure.js';
import { populateReviewNotes } from './notesReview.js';
import { fetchNoteNames } from './fetchUtils.js';

document.addEventListener("DOMContentLoaded", function () {
  async function initializePage() {
    try {
      await populateReviewNotes();
      
      const noteNames = await fetchNoteNames();
      buildFolderTree(noteNames);
      
      renderMarkdown("## Welcome\nSelect a note from the right panel to display it here.");
    } catch (error) {
      console.error("Error initializing page:", error);
    }
  }
  
  initializePage();
});
