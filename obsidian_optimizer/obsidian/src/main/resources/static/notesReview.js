import { NameMapSingleton } from './nameMapSingleton.js';
import { renderMarkdown } from './renderMarkdown.js';
import { fetchNoteContent } from './fetchUtils.js';

const notesToReview = document.getElementById("notes-to-review");

export async function switchNote(event){
    event.preventDefault();
    const content = await fetchNoteContent(fullPath);
    renderMarkdown(content);
}

export async function populateReviewNotes() {
  const fullPaths = await fetchReviewNotes();
  createShortenedNamesMapping(fullPaths);

  notesToReview.innerHTML = "";
  const nameMap = NameMapSingleton.getInstance();

  nameMap.forEach((fullPath, shortName) => {
    const li = document.createElement("li");
    const link = document.createElement("a");
    link.href = "#";
    link.textContent = shortName;
    link.onclick = switchNote;
    li.appendChild(link);
    notesToReview.appendChild(li);
  });
}

async function fetchReviewNotes() {
  try {
    const response = await fetch("http://localhost:8082/review");
    if (!response.ok) throw new Error(`HTTP error! Status: ${response.status}`);
    return response.json();
  } catch (error) {
    console.error("Error fetching review notes:", error);
    return [];
  }
}

function createShortenedNamesMapping(fullPaths) {
  const nameMap = NameMapSingleton.getInstance();
  const usedNames = {};

  fullPaths.forEach((fullPath) => {
    let baseName = fullPath.substring(fullPath.lastIndexOf("\\") + 1).replace(/\.md$/, "");
    let uniqueName = baseName;
    
    let count = usedNames[baseName] || 0;
    while (nameMap.has(uniqueName)) {
      count += 1;
      uniqueName = `${baseName} (${count})`;
    }
    usedNames[baseName] = count;

    nameMap.set(uniqueName, fullPath);
  });
}
