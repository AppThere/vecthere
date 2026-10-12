@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.appthere.vectorcraft

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.posix.memcpy

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private class PickerDelegate(private val onPicked: (List<NSURL>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }
}

/** Files on iPadOS: the system document picker (Files, iCloud Drive, other providers). */
class IosFiles : PlatformFiles {
    /** The picker's delegate, kept alive while the picker shows (UIKit holds it weakly). */
    private var delegate: PickerDelegate? = null

    private fun presenter(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
    }

    override fun open(onPicked: (name: String, bytes: ByteArray) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
        val d = PickerDelegate { urls ->
            val url = urls.firstOrNull() ?: return@PickerDelegate
            val data = NSData.dataWithContentsOfURL(url) ?: return@PickerDelegate
            onPicked(url.lastPathComponent ?: "Untitled", data.toByteArray())
        }
        delegate = d
        picker.delegate = d
        presenter()?.presentViewController(picker, animated = true, completion = null)
    }

    override fun save(suggestedName: String, mimeType: String, bytes: ByteArray, onSaved: () -> Unit) {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + suggestedName)
        if (!bytes.toNSData().writeToURL(url, atomically = true)) return
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
        val d = PickerDelegate { urls -> if (urls.isNotEmpty()) onSaved() }
        delegate = d
        picker.delegate = d
        presenter()?.presentViewController(picker, animated = true, completion = null)
    }
}
