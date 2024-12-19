import { renderMarkdown } from './renderMarkdown.js';
import { fetchNoteContent } from './fetchUtils.js';

const folderStructure = document.getElementById("folder-structure");

// Global name map to store short-to-full path mapping
const nameMap = new Map();

export function buildFolderTree(paths) {
  const root = {};

  // Build the folder tree structure from paths
  paths.forEach((path) => {
    const parts = path.split('\\');
    let current = root;
    parts.forEach((part) => {
      if (!current[part]) {
        current[part] = {};
      }
      current = current[part];
    });

    // Store the mapping of short name (leaf node) to full path
    const baseName = parts[parts.length - 1].replace(/\.md$/, ""); // Remove .md extension
    nameMap.set(baseName, path);  // Add to the global map
  });

  // Function to create the foldable tree with indentation
  function createTreeElement(node, depth = 0) {
    const ul = document.createElement("ul");
    ul.style.listStyleType = "none";
    ul.style.paddingLeft = "0";
    ul.style.marginLeft = "0";

    for (const key in node) {
      const li = document.createElement("li");

      // Create a span to show the folder name and allow click for folding
      const folderName = document.createElement("span");
      folderName.textContent = key;

      // Apply a left margin based on the depth of the folder in the tree
      folderName.style.marginLeft = `${depth * 20}px`;

      // If the node has children (i.e., subfolders), make it foldable
      if (Object.keys(node[key]).length > 0) {
        folderName.style.cursor = "pointer";  // Indicate that the folder is clickable
        folderName.addEventListener("click", () => {
          // Toggle visibility of child folders
          const childUl = li.querySelector("ul");
          if (childUl) {
            childUl.style.display = childUl.style.display === "none" ? "block" : "none";
          }
        });

        // Initially hide child folders
        const childUl = createTreeElement(node[key], depth + 1);  // Increase depth for children
        childUl.style.display = "none";  // Hide by default
        li.appendChild(folderName);
        li.appendChild(childUl);
      } else {
        // If this is a leaf node, make it clickable
        folderName.style.cursor = "pointer";  // Indicate that it's clickable, like a note
        folderName.addEventListener("click", async (event) => {
          event.preventDefault();

          // Trim .md extension from the short name (key) before searching in the map
          const shortName = key.replace(/\.md$/, "");  // Remove .md extension
          //console.log("Attempting to find full path for shortName:", shortName);

          // Get the full path directly from the nameMap
          const fullPath = nameMap.get(shortName);  // Use the global map to find the full path

          if (fullPath) {
            console.log("Full path found:", fullPath);
            const content = await fetchNoteContent(fullPath);  // Use full path for fetching content
            renderMarkdown(content);
          } else {
            console.error(`Full path not found for note: ${shortName}`);
          }
        });

        // Apply similar styles as notes in the "Review Notes" section
        folderName.style.color = "#007BFF";  // A blue color for consistency with clickable notes
        folderName.style.textDecoration = "underline";  // Underline to indicate it's clickable
        li.appendChild(folderName);
      }

      ul.appendChild(li);
    }
    return ul;
  }

  // Clear existing content and append the new folder tree
  folderStructure.innerHTML = "";
  folderStructure.appendChild(createTreeElement(root));
}
