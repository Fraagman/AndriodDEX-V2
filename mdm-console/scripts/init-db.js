const Database = require('better-sqlite3');
const fs = require('fs');
const path = require('path');

const dbPath = path.resolve(__dirname, '../mdm.db');
const schemaPath = path.resolve(__dirname, '../schema.sql');

console.log(`Initializing database at ${dbPath}`);
const db = new Database(dbPath);

const schema = fs.readFileSync(schemaPath, 'utf-8');
db.exec(schema);

console.log('Database initialized successfully.');
db.close();
