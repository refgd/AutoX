import { execSync } from 'child_process'

import fs from 'node:fs/promises';

async function moveDirectory(srcDir, destDir) {
  try {
    // 1. Try to rename/move the directory directly
    await fs.rename(srcDir, destDir);
    console.log(`Directory moved from ${srcDir} to ${destDir} successfully.`);
  } catch (error) {
    if (error.code === 'EXDEV') {
      // 2. Fallback for cross-filesystem moves: copy then remove
      console.log('Cross-filesystem move detected. Copying directory contents...');
      await fs.cp(srcDir, destDir, { recursive: true }); // Requires Node.js v16.0.0+
      await fs.rm(srcDir, { recursive: true, force: true }); // Requires Node.js v14.14.0+
      console.log(`Directory copied and original removed successfully.`);
    } else {
      // 3. Throw any other error
      console.error(`Error moving directory: ${error.message}`);
      throw error;
    }
  }
}

execSync('npm install', { stdio: 'inherit' })

execSync('npm run build', { stdio: 'inherit' })

const directoryPath = '../../assets/v6modules';

try {
  await fs.rm(directoryPath, { recursive: true, force: true });
  console.log(`${directoryPath} is deleted!`);
  await moveDirectory('dist', directoryPath);
} catch (err) {
  console.error(`Error while deleting ${directoryPath}.`, err);
}