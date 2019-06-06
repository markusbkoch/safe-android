package pm.gnosis.heimdall.ui.safe.recover.extension

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.jakewharton.rxbinding2.view.clicks
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import kotlinx.android.synthetic.main.layout_replace_browser_extension.*
import kotlinx.android.synthetic.main.layout_replace_browser_extension_info.*
import pm.gnosis.heimdall.R
import pm.gnosis.heimdall.data.repositories.models.ERC20TokenWithBalance
import pm.gnosis.heimdall.data.repositories.models.SafeTransaction
import pm.gnosis.heimdall.di.components.ViewComponent
import pm.gnosis.heimdall.helpers.AddressHelper
import pm.gnosis.heimdall.reporting.ScreenId
import pm.gnosis.heimdall.ui.base.ViewModelActivity
import pm.gnosis.heimdall.ui.safe.main.SafeMainActivity
import pm.gnosis.heimdall.utils.errorSnackbar
import pm.gnosis.model.Solidity
import pm.gnosis.svalinn.accounts.base.models.Signature
import pm.gnosis.svalinn.common.utils.subscribeForResult
import pm.gnosis.svalinn.common.utils.visible
import pm.gnosis.utils.asEthereumAddress
import pm.gnosis.utils.asEthereumAddressString
import pm.gnosis.utils.hexStringToByteArray
import pm.gnosis.utils.nullOnThrow
import timber.log.Timber
import java.math.BigInteger
import java.math.RoundingMode
import javax.inject.Inject

class ReplaceExtensionSubmitActivity : ViewModelActivity<ReplaceExtensionSubmitContract>() {
    private var submissionInProgress = false

    @Inject
    lateinit var addressHelper: AddressHelper

    override fun layout() = R.layout.layout_replace_browser_extension

    override fun inject(component: ViewComponent) = viewComponent().inject(this)

    override fun screenId() = ScreenId.REPLACE_BROWSER_EXTENSION_REVIEW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val txGas = nullOnThrow { intent.getStringExtra(EXTRA_TX_GAS)?.toBigInteger() } ?: run { finish(); return }
        val dataGas = nullOnThrow { intent.getStringExtra(EXTRA_DATA_GAS)?.toBigInteger() } ?: run { finish(); return }
        val operationalGas = nullOnThrow { intent.getStringExtra(EXTRA_DATA_GAS)?.toBigInteger() } ?: run { finish(); return }
        val gasPrice = nullOnThrow { intent.getStringExtra(EXTRA_GAS_PRICE)?.toBigInteger() } ?: run { finish(); return }
        val gasToken =
            nullOnThrow { intent.getStringExtra(EXTRA_GAS_TOKEN).asEthereumAddress()!! } ?: run { finish(); return }
        val safeTransaction = intent.getParcelableExtra<SafeTransaction>(EXTRA_SAFE_TRANSACTION) ?: run { finish(); return }
        val signature1 = nullOnThrow { Signature.from(intent.getStringExtra(EXTRA_SIGNATURE_1)) } ?: run { finish(); return }
        val signature2 = nullOnThrow { Signature.from(intent.getStringExtra(EXTRA_SIGNATURE_2)) } ?: run { finish(); return }
        val chromeExtensionAddress =
            nullOnThrow { intent.getStringExtra(EXTRA_CHROME_EXTENSION_ADDRESS).asEthereumAddress()!! } ?: run { finish(); return }
        val txHash = nullOnThrow { intent.getStringExtra(EXTRA_TX_HASH).hexStringToByteArray() } ?: run { finish(); return }

        viewModel.setup(safeTransaction, signature1, signature2, txGas, dataGas, operationalGas, gasPrice, gasToken, chromeExtensionAddress, txHash)
    }

    override fun onStart() {
        super.onStart()
        layout_replace_browser_extension_submit.isEnabled = false

        disposables += viewModel.loadFeeInfo()
            .subscribeBy { layout_replace_browser_extension_fee.text = it.displayString(roundingMode = RoundingMode.UP) }

        addressHelper.populateAddressInfo(
            layout_replace_browser_extension_info_safe_address,
            layout_replace_browser_extension_info_safe_name,
            layout_replace_browser_extension_info_safe_image,
            viewModel.getSafeTransaction().wrapped.address
        ).forEach { disposables.add(it) }

        disposables += viewModel.observeSubmitStatus()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeForResult(onNext = ::onSafeBalance, onError = ::onSafeBalanceError)

        disposables += layout_replace_browser_extension_submit.clicks()
            .flatMapSingle { _ ->
                viewModel.submitTransaction()
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSubscribe { onSubmitTransactionProgress(true) }
                    .doAfterTerminate { onSubmitTransactionProgress(false) }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeForResult(onNext = {
                startActivity(SafeMainActivity.createIntent(this, viewModel.getSafeTransaction().wrapped.address, selectedTab = 1))
            }, onError = ::onSubmitTransactionError)

        disposables += layout_replace_browser_extension_back_arrow.clicks()
            .subscribeBy(onNext = { onBackPressed() }, onError = Timber::e)
    }

    private fun onSafeBalance(status: ReplaceExtensionSubmitContract.SubmitStatus) {
        layout_replace_browser_extension_submit.isEnabled = status.canSubmit && !submissionInProgress
        layout_replace_browser_extension_fees_error.visible(!status.canSubmit)
        layout_replace_browser_extension_gas_token_balance.text = status.balance.displayString()
    }

    private fun onSafeBalanceError(throwable: Throwable) {
        if (throwable !is ReplaceExtensionSubmitContract.NoTokenBalanceException) Timber.e(throwable)
        layout_replace_browser_extension_fees_error.visible(false)
        layout_replace_browser_extension_gas_token_balance.text = getString(R.string.error_retrieving_safe_balance)
        layout_replace_browser_extension_submit.isEnabled = false
    }

    private fun onSubmitTransactionProgress(isLoading: Boolean) {
        submissionInProgress = isLoading
        layout_replace_browser_extension_progress.visible(isLoading)
        layout_replace_browser_extension_submit.isEnabled = !isLoading
    }

    private fun onSubmitTransactionError(throwable: Throwable) {
        errorSnackbar(layout_replace_browser_extension_coordinator, throwable)
    }

    companion object {
        private const val EXTRA_SAFE_TRANSACTION = "extra.parcelable.safe_transaction"
        private const val EXTRA_SIGNATURE_1 = "extra.string.signature1"
        private const val EXTRA_SIGNATURE_2 = "extra.string.signature2"
        private const val EXTRA_TX_GAS = "extra.string.tx_gas"
        private const val EXTRA_DATA_GAS = "extra.string.data_gas"
        private const val EXTRA_OPERATIONAL_GAS = "extra.string.operational_gas"
        private const val EXTRA_GAS_PRICE = "extra.string.gas_price"
        private const val EXTRA_GAS_TOKEN = "extra.string.gas_token"
        private const val EXTRA_CHROME_EXTENSION_ADDRESS = "extra.string.chrome_extension_address"
        private const val EXTRA_TX_HASH = "extra.string.tx_hash"

        fun createIntent(
            context: Context,
            safeTransaction: SafeTransaction,
            signature1: Signature,
            signature2: Signature,
            txGas: BigInteger,
            dataGas: BigInteger,
            operationalGas: BigInteger,
            gasPrice: BigInteger,
            gasToken: Solidity.Address,
            chromeExtensionAddress: Solidity.Address,
            txHash: String
        ) =
            Intent(context, ReplaceExtensionSubmitActivity::class.java).apply {
                putExtra(EXTRA_SAFE_TRANSACTION, safeTransaction)
                putExtra(EXTRA_SIGNATURE_1, signature1.toString())
                putExtra(EXTRA_SIGNATURE_2, signature2.toString())
                putExtra(EXTRA_TX_GAS, txGas.toString())
                putExtra(EXTRA_DATA_GAS, dataGas.toString())
                putExtra(EXTRA_OPERATIONAL_GAS, operationalGas.toString())
                putExtra(EXTRA_GAS_PRICE, gasPrice.toString())
                putExtra(EXTRA_GAS_TOKEN, gasToken.asEthereumAddressString())
                putExtra(EXTRA_CHROME_EXTENSION_ADDRESS, chromeExtensionAddress.asEthereumAddressString())
                putExtra(EXTRA_TX_HASH, txHash)
            }
    }
}
