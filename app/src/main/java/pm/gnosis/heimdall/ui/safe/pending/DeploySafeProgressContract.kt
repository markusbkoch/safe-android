package pm.gnosis.heimdall.ui.safe.pending

import android.arch.lifecycle.ViewModel
import io.reactivex.Single
import pm.gnosis.model.Solidity
import java.math.BigInteger

abstract class DeploySafeProgressContract : ViewModel() {
    abstract fun setup(transactionHash: BigInteger?)
    abstract fun notifySafeFunded(): Single<Solidity.Address>
    abstract fun getTransactionHash(): BigInteger?
}
