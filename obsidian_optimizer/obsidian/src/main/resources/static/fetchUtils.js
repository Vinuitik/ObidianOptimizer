export async function fetchNoteNames() {
    try {
      const response = await fetch("/names");
      if (!response.ok) throw new Error(`HTTP error! Status: ${response.status}`);
      return response.json();
    } catch (error) {
      console.error("Error fetching note names:", error);
      return [];
    }
  }
  
export async function fetchNoteContent(noteName) {
    try {
      const response = await fetch(`/text?noteName=${encodeURIComponent(noteName)}`);
      if (!response.ok) throw new Error(`HTTP error! Status: ${response.status}`);
      return response.text();
    } catch (error) {
      console.error("Error fetching note content:", error);
      return "Error loading note content.";
    }
  }
  