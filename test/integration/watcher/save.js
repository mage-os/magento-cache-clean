// Saves a file the way different tools do it.
// Usage: node save.js <pattern> <file>

const fs = require('fs');
const [, , pattern, file] = process.argv;
const content = 'content ' + Math.random();

const patterns = {
    // Plain write to the existing file
    'inplace': () => fs.writeFileSync(file, content),

    // Write a temporary file and move it over the original
    'rename-over': () => {
        fs.writeFileSync(file + '.tmp', content);
        fs.renameSync(file + '.tmp', file);
    },

    // "Safe write" of the JetBrains IDEs
    'jetbrains': () => {
        fs.writeFileSync(file + '___jb_tmp___', content);
        fs.renameSync(file, file + '___jb_old___');
        fs.renameSync(file + '___jb_tmp___', file);
        fs.unlinkSync(file + '___jb_old___');
    },

    // vim with a backup file
    'vim': () => {
        fs.renameSync(file, file + '~');
        fs.writeFileSync(file, content);
        fs.unlinkSync(file + '~');
    },

    // Remove and create again, as git does when switching branches
    'recreate': () => {
        fs.unlinkSync(file);
        fs.writeFileSync(file, content);
    },
};

if (!patterns[pattern]) {
    console.error('Unknown save pattern: ' + pattern);
    process.exit(1);
}
patterns[pattern]();
