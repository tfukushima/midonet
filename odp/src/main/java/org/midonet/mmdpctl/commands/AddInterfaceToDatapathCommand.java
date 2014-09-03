/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.mmdpctl.commands;

import org.midonet.mmdpctl.commands.callables.AddInterfaceToDatapathCallback;
import org.midonet.mmdpctl.commands.results.AddInterfaceToDatapathResult;
import org.midonet.odp.protos.OvsDatapathConnection;

import java.util.concurrent.Future;

public class AddInterfaceToDatapathCommand
        extends Command<AddInterfaceToDatapathResult> {

    private String interfaceName;
    private String datapathName;

    public AddInterfaceToDatapathCommand(String interfaceName,
                                         String datapathName) {
        this.interfaceName = interfaceName;
        this.datapathName =  datapathName;
    }

    public Future<AddInterfaceToDatapathResult>
    execute(OvsDatapathConnection connection) {
        return run(new AddInterfaceToDatapathCallback(
                connection, interfaceName, datapathName));
    }
}
