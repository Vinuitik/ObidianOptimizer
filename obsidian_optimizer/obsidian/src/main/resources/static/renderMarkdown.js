const md = window.markdownit();
const markdownOutput = document.getElementById("markdown-output");

export function renderMarkdown(content) {
  markdownOutput.innerHTML = md.render(content);
}
