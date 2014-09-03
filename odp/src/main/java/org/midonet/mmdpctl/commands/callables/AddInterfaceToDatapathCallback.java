/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.mmdpctl.commands.callables;

import org.midonet.mmdpctl.commands.results.AddInterfaceToDatapathResult;
import org.midonet.odp.Datapath;
import org.midonet.odp.DpPort;
import org.midonet.odp.protos.OvsDatapathConnection;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

public class AddInterfaceToDatapathCallback
        implements Callable<AddInterfaceToDatapathResult> {
    OvsDatapathConnection connection;
    String interfaceName;
    String datapathName;

    public AddInterfaceToDatapathCallback(OvsDatapathConnection connection,
                                          String interfaceName,
                                          String datapathName) {
        this.connection = connection;
        this.interfaceName = interfaceName;
        this.datapathName = datapathName;
    }

    @Override
    public AddInterfaceToDatapathResult call()
            throws InterruptedException, ExecutionException {
        Datapath datapath = connection.futures.datapathsGet(datapathName).get();
        DpPort createdDpPort = connection.futures.addInterface(
                datapath, interfaceName).get();
        boolean isSucceeded = (createdDpPort != null);
        return new AddInterfaceToDatapathResult(datapath, isSucceeded);
    }
}
