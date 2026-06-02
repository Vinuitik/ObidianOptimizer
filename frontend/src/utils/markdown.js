import MarkdownIt from 'markdown-it';

const md = new MarkdownIt({ html: true });

export function renderMarkdown(content) {
  let processed = content.replace(/!\[\[(.*?)\]\]/gs, (_, p1) =>
    `<img src="/api/images/${p1}" alt="${p1}" class="embedded-image" />`
  );
  processed = processed.replace(/\[\[(.*?)\]\]/gs, (_, p1) => p1);
  return md.render(processed);
}
