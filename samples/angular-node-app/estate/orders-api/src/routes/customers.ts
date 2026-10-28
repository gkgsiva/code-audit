import { Router } from 'express';
import { pool } from '../db/pool';

const router = Router();

router.get('/:id', async (req, res) => {
  const result = await pool.query(
    'SELECT c.id, c.name, c.segment FROM CUSTOMERS c WHERE c.id = $1',
    [req.params.id],
  );
  res.json(result.rows[0]);
});

export default router;
