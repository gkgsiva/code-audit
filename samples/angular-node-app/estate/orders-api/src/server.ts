import express from 'express';
import ordersRouter from './routes/orders';
import customersRouter from './routes/customers';
import { AppDataSource } from './db/data-source';
import { startJobs } from './jobs/nightly';

const app = express();
app.use(express.json());
app.use('/api/orders', ordersRouter);
app.use('/api/customers', customersRouter);

app.get('/health', (_req, res) => res.json({ status: 'UP' }));

AppDataSource.initialize().then(() => {
  startJobs();
  app.listen(3000, () => console.log('orders-api listening on 3000'));
});
