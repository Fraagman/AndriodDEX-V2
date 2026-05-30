import { NextResponse } from 'next/server';
import Database from 'better-sqlite3';
import path from 'path';

const dbPath = path.resolve(process.cwd(), 'mdm.db');

export async function GET() {
  try {
    const db = new Database(dbPath);
    const devices = db.prepare('SELECT * FROM devices').all();
    db.close();
    return NextResponse.json(devices);
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function POST(request: Request) {
  try {
    const body = await request.json();
    const { id, name, mac_address, status, last_seen, user_email } = body;
    
    if (!id) {
      return NextResponse.json({ error: 'ID is required' }, { status: 400 });
    }

    const db = new Database(dbPath);
    const stmt = db.prepare(`
      INSERT INTO devices (id, name, mac_address, status, last_seen, user_email) 
      VALUES (?, ?, ?, ?, ?, ?)
    `);
    
    stmt.run(
      id,
      name || 'Unknown Device',
      mac_address || '',
      status || 'active',
      last_seen || Date.now(),
      user_email || ''
    );
    
    db.close();
    return NextResponse.json({ success: true, id }, { status: 201 });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
