package com.ryucortex.onehourlock

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * ロック中でも使える QR コードリーダー。読み取った URL は [WebTabActivity] で開き、
 * その日のうちはロック画面からいつでも開き直せる。
 */
class ScanActivity : Activity() {

    private lateinit var barcodeView: DecoratedBarcodeView

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) = onScanned(result.text.orEmpty().trim())
        override fun possibleResultPoints(resultPoints: List<ResultPoint>) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        barcodeView = DecoratedBarcodeView(this)
        barcodeView.setStatusText("URL の QR コードを枠の中に写してください")
        setContentView(barcodeView)
        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            barcodeView.resume()
            barcodeView.decodeSingle(callback)
        }
    }

    override fun onPause() {
        barcodeView.pause()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!hasCameraPermission()) {
            Toast.makeText(this, "カメラの許可が必要です", Toast.LENGTH_LONG).show()
            finish()
        }
        // 許可された場合は直後の onResume で読み取りが始まる
    }

    private fun onScanned(text: String) {
        val scheme = Uri.parse(text).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Toast.makeText(this, "URL の QR コードではありません", Toast.LENGTH_SHORT).show()
            barcodeView.decodeSingle(callback)
            return
        }
        ScannedTabs.add(this, text)
        startActivity(WebTabActivity.intent(this, text))
        finish()
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
