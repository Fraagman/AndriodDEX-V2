import Link from 'next/link';
import Database from 'better-sqlite3';
import path from 'path';

export const dynamic = 'force-dynamic';

export default function Dashboard() {
  let count = 0;
  let policiesCount = 0;
  try {
    const dbPath = path.resolve(process.cwd(), 'mdm.db');
    const db = new Database(dbPath);
    // Create tables if they don't exist
    db.exec(`
      CREATE TABLE IF NOT EXISTS devices (
          id TEXT PRIMARY KEY,
          name TEXT,
          mac_address TEXT,
          status TEXT,
          last_seen INTEGER,
          user_email TEXT
      );
      CREATE TABLE IF NOT EXISTS policies (
          id TEXT PRIMARY KEY,
          name TEXT,
          clipboard_enabled INTEGER,
          file_transfer_enabled INTEGER,
          max_file_size INTEGER
      );
    `);
    
    const row = db.prepare('SELECT COUNT(*) as count FROM devices').get() as { count: number };
    count = row.count;
    
    const pRow = db.prepare('SELECT COUNT(*) as count FROM policies').get() as { count: number };
    policiesCount = pRow.count;
    db.close();
  } catch (e) {
    console.error('Database query failed', e);
  }

  return (
    <main className="min-h-screen bg-gray-50 p-8">
      <div className="max-w-6xl mx-auto">
        <header className="flex justify-between items-center mb-8 bg-white p-6 rounded-lg shadow-sm border border-gray-200">
          <h1 className="text-3xl font-bold text-gray-900">MDM Console Dashboard</h1>
          <nav className="space-x-4">
            <Link href="/" className="text-blue-600 font-semibold">Dashboard</Link>
            <Link href="/devices" className="text-blue-600 hover:text-blue-800">Devices</Link>
            <Link href="/policies" className="text-blue-600 hover:text-blue-800">Policies</Link>
          </nav>
        </header>
        
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="bg-white p-6 rounded-lg shadow-sm border border-gray-200">
            <h2 className="text-lg font-medium text-gray-600">Total Managed Devices</h2>
            <p className="text-4xl font-bold text-gray-900 mt-2">{count}</p>
          </div>
          
          <div className="bg-white p-6 rounded-lg shadow-sm border border-gray-200">
            <h2 className="text-lg font-medium text-gray-600">Active Policies</h2>
            <p className="text-4xl font-bold text-gray-900 mt-2">{policiesCount}</p>
          </div>
        </div>
      </div>
    </main>
  );
}
