/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2023  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.lsp2.editor

import android.os.SystemClock
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lsp.operations.document.DocumentOpenProvider
import io.github.rosemoe.sora.lsp.operations.document.DocumentSaveProvider
import io.github.rosemoe.sora.lsp2.client.languageserver.requestmanager.RequestManager
import io.github.rosemoe.sora.lsp2.client.languageserver.serverdefinition.LanguageServerDefinition
import io.github.rosemoe.sora.lsp2.client.languageserver.wrapper.LanguageServerWrapper
import io.github.rosemoe.sora.lsp2.editor.event.LspEditorContentChangeEventReceiver
import io.github.rosemoe.sora.lsp2.editor.signature.SignatureHelpWindow
import io.github.rosemoe.sora.lsp2.events.EventType
import io.github.rosemoe.sora.lsp2.events.diagnostics.publishDiagnostics
import io.github.rosemoe.sora.lsp2.events.document.documentClose
import io.github.rosemoe.sora.lsp2.events.document.documentOpen
import io.github.rosemoe.sora.lsp2.events.document.documentSave
import io.github.rosemoe.sora.lsp2.requests.Timeout
import io.github.rosemoe.sora.lsp2.requests.Timeouts
import io.github.rosemoe.sora.lsp2.utils.FileUri
import io.github.rosemoe.sora.lsp2.utils.clearVersions
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextDocumentSyncKind
import java.lang.ref.WeakReference
import java.util.concurrent.TimeoutException
import java.util.function.Consumer

class LspEditor(
    val project: LspProject,
    val uri: FileUri,
) {

    private val serverDefinition: LanguageServerDefinition

    private var _currentEditor: WeakReference<CodeEditor?> = WeakReference(null)

    private var signatureHelpWindowWeakReference: WeakReference<SignatureHelpWindow?> =
        WeakReference(null)

    private lateinit var editorContentChangeEventReceiver: LspEditorContentChangeEventReceiver

    private var currentLanguage: LspLanguage? = null

    private var isClose = false

    private var unsubscribeFunction: Runnable? = null

    val eventManager = LspEventManager(project, this)

    val fileExt = uri.path.substringAfterLast('.')

    var textDocumentSyncKind = TextDocumentSyncKind.Full

    var completionTriggers = emptyList<String>()

    var signatureHelpTriggers = emptyList<String>()

    var signatureHelpReTriggers = emptyList<String>()

    val coroutineScope = project.coroutineScope

    var editor: CodeEditor?
        set(currentEditor) {
            if (currentEditor == null) {
                throw IllegalArgumentException("Editor cannot be null")
            }

            _currentEditor = WeakReference(currentEditor)

            unsubscribeFunction?.run()

            currentEditor.setEditorLanguage(currentLanguage)
            signatureHelpWindowWeakReference = WeakReference(SignatureHelpWindow(currentEditor))


            editorContentChangeEventReceiver = LspEditorContentChangeEventReceiver(this)

            val subscriptionReceipt =
                editor?.subscribeEvent<ContentChangeEvent>(
                    editorContentChangeEventReceiver
                )

            unsubscribeFunction = Runnable { subscriptionReceipt?.unsubscribe() }
        }
        get() {
            return _currentEditor.get()
        }

    var editorContent: String
        get() = editor?.text?.toString() ?: ""
        set(content) {
            editor?.setText(content)
        }

    var wrapperLanguage: Language? = null
        set(language) {
            field = language
            currentLanguage?.wrapperLanguage = wrapperLanguage
            val editor = _currentEditor.get()
            if (editor != null) {
                this.editor = editor
            }
        }

    val languageServerWrapper: LanguageServerWrapper
        get() = project.getOrCreateLanguageServerWrapper(fileExt)

    var diagnostics: List<Diagnostic>
        get() = project.diagnosticsContainer.getDiagnostics(uri)
        set(value) {
            publishDiagnostics(value)
        }

    val isShowSignatureHelp: Boolean
        get() = signatureHelpWindowWeakReference.get()?.isShowing ?: false

    val requestManager: RequestManager?
        get() = languageServerWrapper.requestManager

    init {
        serverDefinition = project.getServerDefinition(fileExt)
            ?: throw Exception("No server definition for extension $fileExt")

        currentLanguage = LspLanguage(this)
    }


    /**
     * Connect to the language server to provide the capabilities, this will cause threads blocking. Note: An error will be thrown if the language server is not connected after some time.
     *
     * @see io.github.rosemoe.sora.lsp.requests.Timeouts
     *
     * @see io.github.rosemoe.sora.lsp.requests.Timeout
     */

    @Throws(TimeoutException::class)
    suspend fun connect(throwException: Boolean = true): Boolean {
        return runCatching {
            languageServerWrapper.start()
            println("finish 1")
            delay(0)
            //wait for language server start
            languageServerWrapper.getServer()
                ?: throw TimeoutException("Unable to connect language server")
           println("finish 2")
            delay(0)
            languageServerWrapper.connect(this)
            println("finish 3")
        }.onFailure {
            if (throwException) {
                throw it
            }
        }.isSuccess
    }

    /**
     * Try to connect to the language server repeatedly, this will cause threads blocking. Note: An error will be thrown if the language server is not connected after some time.
     *
     * @see io.github.rosemoe.sora.lsp.requests.Timeouts
     *
     * @see io.github.rosemoe.sora.lsp.requests.Timeout
     */
    @Throws(InterruptedException::class, TimeoutException::class)
    suspend fun connectWithTimeout() {

        var isConnected = false

        withTimeout(Timeout[Timeouts.INIT].toLong()) {
            while (this.isActive) {
                try {
                    isConnected = connect()
                    if (isConnected) {
                        println("Connected to language server")
                        break
                    }
                } catch (exception: Exception) {
                    exception.printStackTrace();
                    isConnected = false
                }
                delay(100)
            }
        }

        if (!isConnected) {
            throw TimeoutException("Unable to connect language server")
        }

    }

    /**
     * disconnect to the language server
     */
    suspend fun disconnect() {
        runCatching {
            eventManager.emitAsync(EventType.documentClose)

            languageServerWrapper.disconnect(
                this
            )

        }.onFailure {
            throw it
        }
    }

    /**
     * Notify the language server to open the document
     */
    suspend fun openDocument() {
        eventManager.emit(EventType.documentOpen)
    }


    /**
     * Notify language servers the document is saved
     */
    suspend fun saveDocument() {
        eventManager.emit(EventType.documentSave)
    }


    fun onDiagnosticsUpdate() {
        publishDiagnostics(diagnostics)
    }

    private fun publishDiagnostics(diagnostics: List<Diagnostic>) {
        eventManager.emit(EventType.publishDiagnostics, diagnostics)
    }

    fun showSignatureHelp(signatureHelp: SignatureHelp?) {
        val signatureHelpWindow = signatureHelpWindowWeakReference.get() ?: return

        if (signatureHelp == null) {
            editor?.post { signatureHelpWindow.dismiss() }
            return
        }
        editor?.post { signatureHelpWindow.show(signatureHelp) }
    }

    fun hitReTrigger(eventText: CharSequence): Boolean {
        for (trigger in signatureHelpReTriggers) {
            if (trigger.contains(eventText)) {
                return true
            }
        }
        return false
    }

    fun hitTrigger(eventText: CharSequence): Boolean {
        for (trigger in signatureHelpTriggers) {
            if (trigger.contains(eventText)) {
                return true
            }
        }
        return false
    }


    suspend fun dispose() {
        if (isClose) {
            throw IllegalStateException("Editor is already closed")
        }
        disconnect()
        unsubscribeFunction?.run()
        _currentEditor.clear()
        clearVersions {
            it == this.uri
        }
        project.removeEditor(this)
        isClose = true
    }
}