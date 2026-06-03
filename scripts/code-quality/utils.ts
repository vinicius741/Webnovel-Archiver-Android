import fs from "fs";
import path from "path";
import { spawnSync } from "child_process";
import { ROOT, SOURCE_EXTENSIONS, EXCLUDED_DIRS, TEST_DIR_SEGMENTS } from "./constants";

export const logStep = (message: string): void => {
  console.log(`[quality] ${message}`);
};

export const run = (command: string, args: string[], options: Record<string, unknown> = {}) => {
  const result = spawnSync(command, args, {
    cwd: ROOT,
    encoding: "utf8",
    shell: process.platform === "win32",
    ...options,
  });

  if (result.error) {
    throw result.error;
  }

  return result;
};

export const isSourceFile = (filePath: string): boolean =>
  SOURCE_EXTENSIONS.has(path.extname(filePath));

export const isTestFile = (filePath: string): boolean => {
  const parts = filePath.split(/[/\\]/);
  if (parts.some((segment) => TEST_DIR_SEGMENTS.has(segment))) return true;
  const base = path.basename(filePath);
  return base.endsWith(".test.ts") || base.endsWith(".test.tsx") || base.endsWith(".spec.ts") || base.endsWith(".spec.tsx");
};

export const walkFiles = (directory: string): string[] => {
  const absoluteDirectory = path.join(ROOT, directory);
  if (!fs.existsSync(absoluteDirectory)) {
    return [];
  }

  const files: string[] = [];
  const entries = fs.readdirSync(absoluteDirectory, { withFileTypes: true });

  for (const entry of entries) {
    const absolutePath = path.join(absoluteDirectory, entry.name);
    const relativePath = path.relative(ROOT, absolutePath);

    if (entry.isDirectory()) {
      if (EXCLUDED_DIRS.has(entry.name)) {
        continue;
      }
      files.push(...walkFiles(relativePath));
      continue;
    }

    if (entry.isFile() && isSourceFile(absolutePath) && !absolutePath.endsWith(".d.ts") && !isTestFile(relativePath)) {
      files.push(relativePath);
    }
  }

  return files;
};

export const countLines = (filePath: string): number => {
  const content = fs.readFileSync(path.join(ROOT, filePath), "utf8");
  if (content.length === 0) {
    return 0;
  }
  return content.split(/\r\n|\r|\n/).length;
};
