package com.acme.billing;

/** Monthly billing run: one invoice per active customer. */
public final class BillingMain {
    private BillingMain() {
    }

    public static void main(String[] args) {
        System.out.println("billing " + String.join(" ", args));
    }
}
