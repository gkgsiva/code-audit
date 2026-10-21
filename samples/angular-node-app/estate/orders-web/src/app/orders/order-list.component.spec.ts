import { OrderListComponent } from './order-list.component';

describe('OrderListComponent', () => {
  it('reloads on init', () => {
    const service = { list: jasmine.createSpy('list') };
    const c = new OrderListComponent(service as never);
    c.ngOnInit();
    expect(service.list).toHaveBeenCalled();
  });
});
