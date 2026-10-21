package com.acme.orders.rest;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/** REST resource for the order screens of the new front end. */
@Path("/orders")
public class OrderResource {

    @GET
    @Path("/{id}")
    public String get(@PathParam("id") long id) {
        return "{}";
    }

    @POST
    public String create(String body) {
        return body;
    }
}
