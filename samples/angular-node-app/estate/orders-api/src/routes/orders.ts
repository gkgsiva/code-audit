import { Router } from 'express';
import { OrderService } from '../services/order.service';

const router = Router();
const service = new OrderService();

router.get('/', async (req, res) => {
  res.json(await service.search(req.query.status as string | undefined));
});

router.get('/:id', async (req, res) => {
  res.json(await service.get(Number(req.params.id)));
});

router.post('/', async (req, res) => {
  res.status(201).json(await service.create(req.body));
});

router.delete('/:id', async (req, res) => {
  await service.cancel(Number(req.params.id));
  res.status(204).end();
});

export default router;
