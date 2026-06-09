/**
 * Post-process Milkdown's serialized markdown output to remove artifacts
 * that would corrupt Obsidian notes:
 *
 * <br /> lines: Milkdown emits these for empty paragraphs. Obsidian doesn't
 * understand the convention; a blank line is enough.
 *
 * Note: \# unescaping is no longer needed — hashtagPlugin serializes #tags
 * verbatim via the html node type, so mdast-util-to-markdown never sees them.
 */
export function cleanMilkdownOutput(md) {
  return md.replace(/^<br\s*\/?>$/gm, '');
}
