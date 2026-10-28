import cron from 'node-cron';
import { pool } from '../db/pool';

// Nightly clean-up: removes order audit rows older than 90 days.
export async function purgeOldOrders(): Promise<void> {
  await pool.query(`DELETE FROM ORDER_AUDIT WHERE created_at < now() - interval '90 days'`);
}

export function startJobs(): void {
  cron.schedule('0 2 * * *', () => purgeOldOrders(), { name: 'purge-old-orders' });
}
