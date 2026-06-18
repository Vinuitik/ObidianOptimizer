import { $node, $remark } from '@milkdown/utils';

// ── Module-level blob registry: filename → blobURL (for pre-save rendering) ──

const pendingBlobRegistry = new Map();

/** Replace the entire registry (called on tab switch / pendingFiles store change). */
export function setPendingBlobs(pendingFilesMap) {
  pendingBlobRegistry.clear();
  for (const [filename, { blobURL }] of Object.entries(pendingFilesMap)) {
    pendingBlobRegistry.set(filename, blobURL);
  }
}

/** Add a single entry immediately (called in the paste handler before ProseMirror renders). */
export function addPendingBlob(filename, blobURL) {
  pendingBlobRegistry.set(filename, blobURL);
}

/** Remove a single entry after upload completes. */
export function removePendingBlob(filename) {
  pendingBlobRegistry.delete(filename);
}

// ── File-type helpers ─────────────────────────────────────────────────────────

const IMAGE_EXTS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg']);
const VIDEO_EXTS = new Set(['.mp4', '.mov', '.mkv', '.webm', '.avi']);
const AUDIO_EXTS = new Set(['.mp3', '.wav', '.ogg', '.m4a', '.flac']);
const PDF_EXTS   = new Set(['.pdf']);

export function fileTypeFor(filename) {
  const dot = filename.lastIndexOf('.');
  if (dot < 0) return 'other';
  const ext = filename.slice(dot).toLowerCase();
  if (IMAGE_EXTS.has(ext)) return 'image';
  if (VIDEO_EXTS.has(ext)) return 'video';
  if (AUDIO_EXTS.has(ext)) return 'audio';
  if (PDF_EXTS.has(ext))   return 'pdf';
  return 'other';
}

export const WHITELISTED_EXTS = new Set([
  ...IMAGE_EXTS, ...VIDEO_EXTS, ...AUDIO_EXTS, ...PDF_EXTS,
]);

export const WHITELISTED_MIME_TYPES = new Set([
  'image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/svg+xml',
  'video/mp4', 'video/quicktime', 'video/x-matroska', 'video/webm', 'video/x-msvideo',
  'audio/mpeg', 'audio/wav', 'audio/ogg', 'audio/mp4', 'audio/flac',
  'application/pdf',
]);

export function isWhitelisted(file) {
  if (WHITELISTED_MIME_TYPES.has(file.type)) return true;
  const name = file.name.toLowerCase();
  const dot  = name.lastIndexOf('.');
  return dot >= 0 && WHITELISTED_EXTS.has(name.slice(dot));
}

// ── Filename generator: stem-{timestamp}{8randomhex}.ext ─────────────────────

export function generateFilename(file) {
  const name = file.name;
  const dotIdx = name.lastIndexOf('.');
  const ext  = dotIdx >= 0 ? name.slice(dotIdx).toLowerCase() : '';
  const stem = (dotIdx >= 0 ? name.slice(0, dotIdx) : name)
    .replace(/[^a-zA-Z0-9-_]/g, '_')
    .slice(0, 40);
  const ts   = Date.now();
  const rand = Array.from(crypto.getRandomValues(new Uint8Array(4)))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
  return `${stem}-${ts}${rand}${ext}`;
}

// ── Remark plugin: parse ![[filename]] in the mdast ──────────────────────────

function obsidianImageRemarkPlugin() {
  return (tree) => {
    visit(tree, 'text', (node, index, parent) => {
      if (!parent || index == null) return;
      const RE = /!\[\[([^\]]+?)\]\]/g;
      const parts = [];
      let last = 0;
      let match;
      while ((match = RE.exec(node.value)) !== null) {
        if (match.index > last) {
          parts.push({ type: 'text', value: node.value.slice(last, match.index) });
        }
        parts.push({
          type: 'obsidianImage',
          filename: match[1].trim(),
          data: { hName: 'img' },
        });
        last = match.index + match[0].length;
      }
      if (parts.length === 0) return;
      if (last < node.value.length) {
        parts.push({ type: 'text', value: node.value.slice(last) });
      }
      parent.children.splice(index, 1, ...parts);
    });
  };
}

function visit(tree, type, visitor) {
  function walk(node, index, parent) {
    if (node.type === type) visitor(node, index, parent);
    if (node.children) {
      for (let i = node.children.length - 1; i >= 0; i--) {
        walk(node.children[i], i, node);
      }
    }
  }
  walk(tree, null, null);
}

// ── $remark ───────────────────────────────────────────────────────────────────

export const obsidianImageRemark$ = $remark('obsidianImage', () => obsidianImageRemarkPlugin);

// ── URL builder: resolves filename → nginx-served path (range requests work) ──

function subdirFor(filename) {
  const type = fileTypeFor(filename);
  if (type === 'image') return 'images';
  if (type === 'video') return 'videos';
  if (type === 'audio') return 'audio';
  if (type === 'pdf')   return 'pdf';
  return 'files';
}

function mediaUrl(filename) {
  return `/vault-media/${subdirFor(filename)}/${encodeURIComponent(filename)}`;
}

// ── $node: renders as appropriate element, serializes back to ![[...]] ────────

export const obsidianImageNode$ = $node('obsidian_image', () => ({
  group: 'inline',
  inline: true,
  atom: true,
  attrs: {
    filename: { default: '' },
  },
  toDOM(node) {
    const { filename } = node.attrs;
    const src = pendingBlobRegistry.get(filename) ?? mediaUrl(filename);
    const type = fileTypeFor(filename);

    if (type === 'video') {
      return ['video', {
        class: 'embedded-video',
        src,
        controls: '',
        'data-obsidian-image': filename,
      }];
    }
    if (type === 'audio') {
      return ['audio', {
        class: 'embedded-audio',
        src,
        controls: '',
        'data-obsidian-image': filename,
      }];
    }
    if (type === 'pdf') {
      return ['a', {
        class: 'embedded-file',
        href: src,
        target: '_blank',
        rel: 'noopener noreferrer',
        'data-obsidian-image': filename,
      }, `📄 ${filename}`];
    }
    if (type === 'other') {
      return ['a', {
        class: 'embedded-file',
        href: src,
        target: '_blank',
        rel: 'noopener noreferrer',
        'data-obsidian-image': filename,
      }, `📎 ${filename}`];
    }
    // image / svg (default)
    return ['img', {
      class: 'embedded-image',
      src,
      alt: filename,
      'data-obsidian-image': filename,
    }];
  },
  parseDOM: [{
    tag: '[data-obsidian-image]',
    getAttrs(dom) {
      return { filename: dom.getAttribute('data-obsidian-image') ?? '' };
    },
  }],
  parseMarkdown: {
    match: node => node.type === 'obsidianImage',
    runner: (state, node, type) => {
      state.addNode(type, { filename: node.filename });
    },
  },
  toMarkdown: {
    match: node => node.type.name === 'obsidian_image',
    runner: (state, node) => {
      state.addNode('html', undefined, `![[${node.attrs.filename}]]`);
    },
  },
}));

// ── Export shape ──────────────────────────────────────────────────────────────

export const obsidianImagePlugin = [
  ...obsidianImageRemark$,
  obsidianImageNode$,
];
