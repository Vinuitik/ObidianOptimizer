import { renderMarkdown } from './renderMarkdown.js';
import { buildFolderTree } from './folderStructure.js';
import { populateReviewNotes } from './notesReview.js';
import { fetchNoteNames } from './fetchUtils.js';

window.togglePanel = function(panelId) {
  const panel = document.getElementById(panelId);
  const btn = panel.querySelector('.collapse-btn');
  const isLeft = panelId === 'left-panel';
  panel.classList.toggle('collapsed');
  const collapsed = panel.classList.contains('collapsed');
  btn.textContent = collapsed ? (isLeft ? '▶' : '◀') : (isLeft ? '◀' : '▶');
};

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
