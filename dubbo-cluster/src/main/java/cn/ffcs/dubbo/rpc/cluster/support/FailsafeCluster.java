package cn.ffcs.dubbo.rpc.cluster.support;

import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.wrapper.AbstractCluster;

public class FailsafeCluster extends AbstractCluster {
    public static final String NAME = "failsafe-ffcs";

    public FailsafeCluster() {
    }

    public <T> AbstractClusterInvoker<T> doJoin(Directory<T> directory) throws RpcException {
        return new FailsafeClusterInvoker(directory);
    }
}
