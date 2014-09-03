/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.mmdpctl.commands.results;

import org.midonet.odp.Datapath;

import java.io.OutputStream;
import java.io.PrintStream;

public class AddInterfaceToDatapathResult implements Result {

    Datapath datapath;
    boolean isSucceeded;

    public AddInterfaceToDatapathResult(Datapath datapath, boolean isSucceeded) {
        this.datapath = datapath;
        this.isSucceeded = isSucceeded;
    }

    @Override
    public void printResult(OutputStream stream) {
        PrintStream out = new PrintStream(stream);
        if (isSucceeded) {
            out.println("Succeeded to add the interface to the datapath.");
        } else {
            out.println("Failed to add the interface to the datapath.");
        }
    }

    @Override
    public void printResult() {
        printResult(System.out);
    }
}
