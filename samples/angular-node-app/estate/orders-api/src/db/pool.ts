import { Pool } from 'pg';

// Connection pool for hand-written queries (reports, customers).
export const pool = new Pool({ connectionString: process.env.DATABASE_URL });
