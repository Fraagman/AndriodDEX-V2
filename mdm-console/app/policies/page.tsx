import Link from 'next/link';
import Database from 'better-sqlite3';
import path from 'path';
import { revalidatePath } from 'next/cache';

export const dynamic = 'force-dynamic';

async function createPolicy(formData: FormData) {
  'use server';
  const id = formData.get('id') as string;
  const name = formData.get('name') as string;
  const clipboard_enabled = formData.get('clipboard_enabled') === 'on' ? 1 : 0;
  const file_transfer_enabled = formData.get('file_transfer_enabled') === 'on' ? 1 : 0;
  const max_file_size = parseInt(formData.get('max_file_size') as string) || 0;

  if (!id || !name) return;

  const dbPath = path.resolve(process.cwd(), 'mdm.db');
  const db = new Database(dbPath);
  
  try {
    const stmt = db.prepare(`
      INSERT OR REPLACE INTO policies (id, name, clipboard_enabled, file_transfer_enabled, max_file_size) 
      VALUES (?, ?, ?, ?, ?)
    `);
    stmt.run(id, name, clipboard_enabled, file_transfer_enabled, max_file_size);
  } catch (e) {
    console.error('Failed to create policy', e);
  } finally {
    db.close();
  }

  revalidatePath('/policies');
  revalidatePath('/');
}

export default async function PoliciesPage() {
  let policies: any[] = [];
  try {
    const dbPath = path.resolve(process.cwd(), 'mdm.db');
    const db = new Database(dbPath);
    policies = db.prepare('SELECT * FROM policies').all();
    db.close();
  } catch (e) {
    console.error('Database query failed', e);
  }

  return (
    <main className="min-h-screen bg-gray-50 p-8">
      <div className="max-w-6xl mx-auto">
        <header className="flex justify-between items-center mb-8 bg-white p-6 rounded-lg shadow-sm border border-gray-200">
          <h1 className="text-3xl font-bold text-gray-900">Policies Management</h1>
          <nav className="space-x-4">
            <Link href="/" className="text-blue-600 hover:text-blue-800">Dashboard</Link>
            <Link href="/devices" className="text-blue-600 hover:text-blue-800">Devices</Link>
            <Link href="/policies" className="text-blue-600 font-semibold">Policies</Link>
          </nav>
        </header>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
          <div className="bg-white p-6 rounded-lg shadow-sm border border-gray-200">
            <h2 className="text-xl font-bold mb-4">Create New Policy</h2>
            <form action={createPolicy} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700">Policy ID</label>
                <input type="text" name="id" required className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm p-2" />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700">Policy Name</label>
                <input type="text" name="name" required className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm p-2" />
              </div>
              <div className="flex items-center">
                <input type="checkbox" name="clipboard_enabled" className="h-4 w-4 text-blue-600 border-gray-300 rounded" />
                <label className="ml-2 block text-sm text-gray-900">Enable Clipboard Sync</label>
              </div>
              <div className="flex items-center">
                <input type="checkbox" name="file_transfer_enabled" className="h-4 w-4 text-blue-600 border-gray-300 rounded" />
                <label className="ml-2 block text-sm text-gray-900">Enable File Transfer</label>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700">Max File Size (MB)</label>
                <input type="number" name="max_file_size" defaultValue="100" className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm p-2" />
              </div>
              <button type="submit" className="w-full bg-blue-600 text-white p-2 rounded-md hover:bg-blue-700 font-semibold">
                Create Policy
              </button>
            </form>
          </div>

          <div className="bg-white p-6 rounded-lg shadow-sm border border-gray-200">
            <h2 className="text-xl font-bold mb-4">Existing Policies</h2>
            <ul className="divide-y divide-gray-200">
              {policies.map((policy) => (
                <li key={policy.id} className="py-4">
                  <div className="flex justify-between">
                    <div>
                      <p className="text-sm font-medium text-gray-900">{policy.name} ({policy.id})</p>
                      <p className="text-sm text-gray-500">
                        Clipboard: {policy.clipboard_enabled ? 'Yes' : 'No'} | 
                        File Transfer: {policy.file_transfer_enabled ? 'Yes' : 'No'} | 
                        Max File Size: {policy.max_file_size}MB
                      </p>
                    </div>
                  </div>
                </li>
              ))}
              {policies.length === 0 && (
                <li className="py-4 text-sm text-gray-500">No policies found.</li>
              )}
            </ul>
          </div>
        </div>
      </div>
    </main>
  );
}
