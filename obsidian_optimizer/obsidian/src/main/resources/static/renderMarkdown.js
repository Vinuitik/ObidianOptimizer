import { switchNote } from "./notesReview";



const md = window.markdownit({ html: true });
const markdownOutput = document.getElementById("markdown-output");

function processText(text) {
  console.log('Processing text:', text);

  // Extract and replace ![[image]] syntax
  const imageLinks = [];
  text = text.replace(/!\[\[(.*?)\]\]/gs, (match, p1) => {
      imageLinks.push(p1);
      // Replace with <img> HTML tag
      return `<img src="images/${p1}" alt="${p1}" class="embedded-image" />`;
  });

  // Extract all content inside [[...]]
  const links = [];
  text = text.replace(/\[\[(.*?)\]\]/gs, (match, p1) => {
      links.push(p1);
      // Remove but keep the text clean
      return `<a href="#" onclick = "switchNote" />${p1}</a>`; // implement onclick
  });

  return { imageLinks, links, remainingText: text };
}

export function renderMarkdown(content) {
  // Process the text
  let contentCopy = content;
  const { bangLinks, links, remainingText } = processText(contentCopy); // Match field names

  console.log("Collected from ![[...]]:", bangLinks);
  console.log("Collected from [[...]]:", links);
  console.log("Remaining text:", remainingText);

  markdownOutput.innerHTML = md.render(remainingText);
  console.log(content);
  console.log(markdownOutput.innerHTML);
}


/*

function processText(text) {
    console.log('Processing text:', text);

    // Extract and replace ![[image]] syntax
    const imageLinks = [];
    text = text.replace(/!\[\[(.*?)\]\]/gs, (match, p1) => {
        imageLinks.push(p1);
        // Replace with <img> HTML tag
        return `<img src="${p1}" alt="${p1}" class="embedded-image" />`;
    });

    // Extract all content inside [[...]]
    const links = [];
    text = text.replace(/\[\[(.*?)\]\]/gs, (match, p1) => {
        links.push(p1);
        // Remove but keep the text clean
        return '';
    });

    return { imageLinks, links, remainingText: text };
}

*/
