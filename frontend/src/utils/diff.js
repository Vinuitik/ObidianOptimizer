// LCS-based diff that produces a minimal set of hunks to transform oldText → newText.
// Hunk shape: { startLine: number, deleteCount: number, insertLines: string[] }
// startLine is 0-indexed in the original.
// Hunks must be applied back-to-front (highest startLine first) to preserve indices.

function lcsBacktrack(a, b) {
  const m = a.length, n = b.length;
  const dp = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));

  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      dp[i][j] = a[i - 1] === b[j - 1]
        ? dp[i - 1][j - 1] + 1
        : Math.max(dp[i - 1][j], dp[i][j - 1]);
    }
  }

  const ops = [];
  let i = m, j = n;
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && a[i - 1] === b[j - 1]) {
      ops.push({ op: 'equal' });
      i--; j--;
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      ops.push({ op: 'insert', line: b[j - 1] });
      j--;
    } else {
      ops.push({ op: 'delete' });
      i--;
    }
  }
  return ops.reverse();
}

// Returns an array of hunks. Empty array means no changes.
export function computeHunks(oldText, newText) {
  if (oldText === newText) return [];

  // Normalize CRLF → LF so browser-edited content (LF) matches server content (CRLF)
  const oldLines = oldText.replace(/\r\n/g, '\n').split('\n');
  const newLines = newText.replace(/\r\n/g, '\n').split('\n');

  const ops = lcsBacktrack(oldLines, newLines);
  const hunks = [];
  let srcLine = 0;
  let i = 0;

  while (i < ops.length) {
    if (ops[i].op === 'equal') {
      srcLine++;
      i++;
      continue;
    }

    const startLine = srcLine;
    let deleteCount = 0;
    const insertLines = [];

    while (i < ops.length && ops[i].op !== 'equal') {
      if (ops[i].op === 'delete') {
        deleteCount++;
        srcLine++;
      } else {
        insertLines.push(ops[i].line);
      }
      i++;
    }

    hunks.push({ startLine, deleteCount, insertLines });
  }

  return hunks;
}

// Apply a set of hunks (produced by computeHunks) to reconstruct the modified text.
// Hunks are sorted back-to-front so splicing doesn't shift subsequent indices.
export function applyHunks(text, hunks) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const sorted = [...hunks].sort((a, b) => b.startLine - a.startLine);
  for (const { startLine, deleteCount, insertLines } of sorted) {
    lines.splice(startLine, deleteCount, ...insertLines);
  }
  return lines.join('\n');
}
