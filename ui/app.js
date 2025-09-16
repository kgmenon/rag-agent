class RAGApp {
    constructor() {
        this.apiBaseUrl = '';
        this.uploadedFiles = new Map();
        this.currentUploads = new Map();
        
        // File size limit: 50MB
        this.maxFileSize = 50 * 1024 * 1024; // 50MB in bytes
        this.supportedTypes = ['.pdf', '.txt', '.doc', '.docx', '.md'];
        
        this.initializeElements();
        this.setupEventListeners();
        this.loadApiConfig();
    }

    initializeElements() {
        this.uploadArea = document.getElementById('uploadArea');
        this.fileInput = document.getElementById('fileInput');
        this.uploadProgress = document.getElementById('uploadProgress');
        this.progressText = document.getElementById('progressText');
        this.progressPercent = document.getElementById('progressPercent');
        this.progressFill = document.getElementById('progressFill');
        this.uploadedFilesContainer = document.getElementById('uploadedFiles');
        this.chatMessages = document.getElementById('chatMessages');
        this.queryInput = document.getElementById('queryInput');
        this.sendButton = document.getElementById('sendButton');
        this.errorModal = document.getElementById('errorModal');
        this.errorMessage = document.getElementById('errorMessage');
    }

    setupEventListeners() {
        console.log('🔧 DEBUG: Setting up event listeners');
        
        // File upload events
        this.uploadArea.addEventListener('click', () => {
            console.log('🖱️ DEBUG: Upload area clicked, triggering file input');
            this.fileInput.click();
        });
        
        this.fileInput.addEventListener('change', (e) => {
            console.log('📁 DEBUG: File input changed, files selected:', e.target.files.length);
            this.handleFileSelection(e.target.files);
        });
        
        // Drag and drop events
        this.uploadArea.addEventListener('dragover', (e) => {
            e.preventDefault();
            this.uploadArea.classList.add('dragover');
            console.log('🔄 DEBUG: Drag over upload area');
        });
        
        this.uploadArea.addEventListener('dragleave', () => {
            this.uploadArea.classList.remove('dragover');
            console.log('🔄 DEBUG: Drag leave upload area');
        });
        
        this.uploadArea.addEventListener('drop', (e) => {
            e.preventDefault();
            this.uploadArea.classList.remove('dragover');
            console.log('📤 DEBUG: Files dropped! Count:', e.dataTransfer.files.length);
            console.log('📤 DEBUG: Dropped files:', Array.from(e.dataTransfer.files).map(f => f.name));
            this.handleFileSelection(e.dataTransfer.files);
        });

        // Chat events
        this.sendButton.addEventListener('click', () => this.sendQuery());
        this.queryInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendQuery();
            }
        });
    }

    async loadApiConfig() {
        try {
            console.log('Loading API config from /config.json...');
            const response = await fetch('/config.json');
            console.log('Config response status:', response.status);
            if (response.ok) {
                const config = await response.json();
                console.log('Loaded config:', config);
                this.apiBaseUrl = config.apiBaseUrl;
                console.log('API Base URL set to:', this.apiBaseUrl);
            } else {
                console.error('Failed to load config, response not ok:', response.status);
                this.apiBaseUrl = prompt('Please enter the API Gateway URL:');
                if (!this.apiBaseUrl) {
                    this.showError('API Gateway URL is required');
                    return;
                }
            }
            
            if (this.apiBaseUrl && !this.apiBaseUrl.endsWith('/')) {
                this.apiBaseUrl += '/';
            }
            
            this.enableChatIfReady();
            this.requestNotificationPermission();
        } catch (error) {
            this.apiBaseUrl = prompt('Please enter the API Gateway URL:');
            if (!this.apiBaseUrl) {
                this.showError('API Gateway URL is required');
                return;
            }
            
            if (!this.apiBaseUrl.endsWith('/')) {
                this.apiBaseUrl += '/';
            }
            
            this.enableChatIfReady();
            this.requestNotificationPermission();
        }
    }

    requestNotificationPermission() {
        if ('Notification' in window && Notification.permission === 'default') {
            Notification.requestPermission().then((permission) => {
                if (permission === 'granted') {
                    console.log('✅ Notification permission granted');
                } else {
                    console.log('ℹ️ Notification permission denied');
                }
            });
        }
    }

    async handleFileSelection(files) {
        console.log('🚀 DEBUG: === handleFileSelection STARTED ===');
        console.log('🚀 DEBUG: File count:', files.length);
        console.log('🚀 DEBUG: Current API Base URL:', this.apiBaseUrl);
        console.log('🚀 DEBUG: Files to process:', Array.from(files).map(f => ({name: f.name, size: f.size, type: f.type})));
        
        const filesToUpload = [];
        let hasErrors = false;
        
        // Validate all files first
        for (const file of files) {
            // Check if file already uploaded
            if (this.uploadedFiles.has(file.name)) {
                this.showError(`File "${file.name}" has already been uploaded.`);
                hasErrors = true;
                continue;
            }
            
            // Validate file
            const validationErrors = this.validateFileBeforeUpload(file);
            if (validationErrors.length > 0) {
                const errorMessage = `File "${file.name}":\n• ${validationErrors.join('\n• ')}`;
                this.showError(errorMessage);
                hasErrors = true;
                continue;
            }
            
            filesToUpload.push(file);
        }
        
        // Show success message for valid files
        if (filesToUpload.length > 0) {
            const totalSize = filesToUpload.reduce((sum, file) => sum + file.size, 0);
            const fileNames = filesToUpload.map(f => f.name).join(', ');
            console.log(`✅ Ready to upload ${filesToUpload.length} file(s): ${fileNames} (Total: ${this.formatFileSize(totalSize)})`);
            this.showInfo(`Starting upload of ${filesToUpload.length} file(s) (${this.formatFileSize(totalSize)})`);
        }
        
        // Upload valid files
        console.log('🎯 DEBUG: Starting upload loop for', filesToUpload.length, 'valid files');
        for (const file of filesToUpload) {
            console.log('🎯 DEBUG: Processing file:', file.name);
            this.addFileToList(file.name, 'uploading');
            await this.uploadFile(file);
        }
        console.log('🏁 DEBUG: === handleFileSelection COMPLETED ===');
    }

    addFileToList(fileName, status) {
        const fileItem = document.createElement('div');
        fileItem.className = 'file-item';
        fileItem.innerHTML = `
            <span class="file-name">${fileName}</span>
            <span class="file-status ${status}">${this.getStatusText(status)}</span>
        `;
        this.uploadedFilesContainer.appendChild(fileItem);
        return fileItem;
    }

    updateFileStatus(fileName, status) {
        const fileItems = this.uploadedFilesContainer.querySelectorAll('.file-item');
        for (const item of fileItems) {
            const nameSpan = item.querySelector('.file-name');
            if (nameSpan.textContent === fileName) {
                const statusSpan = item.querySelector('.file-status');
                statusSpan.className = `file-status ${status}`;
                statusSpan.textContent = this.getStatusText(status);
                break;
            }
        }
    }

    getStatusText(status) {
        switch (status) {
            case 'uploading': return 'Uploading...';
            case 'processing': return 'Processing...';
            case 'completed': return 'Ready';
            case 'error': return 'Error';
            default: return status;
        }
    }

    async uploadFile(file) {
        try {
            console.log('⬆️ DEBUG: === uploadFile STARTED ===');
            console.log('⬆️ DEBUG: File:', file.name, 'Size:', this.formatFileSize(file.size));
            console.log('⬆️ DEBUG: API URL:', this.apiBaseUrl);
            this.showProgress(`Uploading ${file.name} (${this.formatFileSize(file.size)})...`, 0);
            
            console.log('⬆️ DEBUG: Step 1 - Calling initiateUpload...');
            const { uploadId, key, bucket } = await this.initiateUpload(file);
            console.log('⬆️ DEBUG: Step 1 - Upload initiated:', { uploadId, key, bucket });
            
            console.log('⬆️ DEBUG: Step 2 - Uploading parts...');
            const parts = await this.uploadParts(file, uploadId, key, bucket);
            console.log('⬆️ DEBUG: Step 2 - Parts uploaded:', parts.length, 'parts');
            
            console.log('⬆️ DEBUG: Step 3 - Completing upload...');
            await this.completeUpload(uploadId, key, bucket, parts);
            console.log('⬆️ DEBUG: Step 3 - Upload completed successfully');
            
            this.uploadedFiles.set(file.name, { key, bucket });
            this.updateFileStatus(file.name, 'processing');
            this.hideProgress();
            
            this.showSuccess(`Upload completed: ${file.name}. Document processing started.`);
            
            // Real document processing happens on the backend, so give it more time
            setTimeout(() => {
                this.updateFileStatus(file.name, 'completed');
                this.enableChatIfReady();
                this.showSuccess(`Document ${file.name} is now ready for questions!`);
            }, 5000);
            
        } catch (error) {
            console.error('❌ DEBUG: === uploadFile FAILED ===');
            console.error('❌ DEBUG: File:', file.name);
            console.error('❌ DEBUG: Error:', error);
            console.error('❌ DEBUG: Error message:', error.message);
            console.error('❌ DEBUG: Error stack:', error.stack);
            this.updateFileStatus(file.name, 'error');
            this.hideProgress();
            this.showError(`Failed to upload ${file.name}: ${error.message}`);
        }
    }

    async initiateUpload(file) {
        console.log('🚀 DEBUG: === initiateUpload STARTED ===');
        console.log('🚀 DEBUG: Making POST request to:', `${this.apiBaseUrl}upload/initiate`);
        console.log('🚀 DEBUG: Request body:', { fileName: file.name, contentType: file.type || 'application/octet-stream' });
        
        const response = await fetch(`${this.apiBaseUrl}upload/initiate`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                fileName: file.name,
                contentType: file.type || 'application/octet-stream'
            })
        });

        console.log('🚀 DEBUG: Response status:', response.status, response.statusText);
        console.log('🚀 DEBUG: Response headers:', Object.fromEntries(response.headers.entries()));

        if (!response.ok) {
            const errorText = await response.text();
            console.error('🚀 DEBUG: Response error body:', errorText);
            throw new Error(`Upload initiation failed: ${response.statusText} - ${errorText}`);
        }

        const result = await response.json();
        console.log('🚀 DEBUG: initiate response:', result);
        return result;
    }

    async uploadParts(file, uploadId, key, bucket) {
        const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB chunks
        const parts = [];
        let partNumber = 1;

        for (let start = 0; start < file.size; start += CHUNK_SIZE) {
            const end = Math.min(start + CHUNK_SIZE, file.size);
            const chunk = file.slice(start, end);

            const presignedUrl = await this.getPresignedUrl(bucket, key, uploadId, partNumber);
            const etag = await this.uploadChunk(chunk, presignedUrl);

            parts.push({
                PartNumber: partNumber,
                ETag: etag
            });

            const progress = Math.round((end / file.size) * 100);
            this.updateProgress(progress);
            
            partNumber++;
        }

        return parts;
    }

    async getPresignedUrl(bucket, key, uploadId, partNumber) {
        const params = new URLSearchParams({
            bucket,
            key,
            uploadId,
            partNumber: partNumber.toString()
        });

        const response = await fetch(`${this.apiBaseUrl}upload/presignPart?${params}`);
        
        if (!response.ok) {
            throw new Error(`Failed to get presigned URL: ${response.statusText}`);
        }

        const data = await response.json();
        return data.presignedUrl;
    }

    async uploadChunk(chunk, presignedUrl) {
        console.log('📦 DEBUG: Uploading chunk to presigned URL');
        const response = await fetch(presignedUrl, {
            method: 'PUT',
            body: chunk
        });

        console.log('📦 DEBUG: Chunk upload response status:', response.status, response.statusText);
        console.log('📦 DEBUG: Response headers:', Object.fromEntries(response.headers.entries()));

        if (!response.ok) {
            throw new Error(`Chunk upload failed: ${response.statusText}`);
        }

        const etag = response.headers.get('ETag');
        console.log('📦 DEBUG: Extracted ETag:', etag);
        
        if (!etag) {
            console.error('📦 DEBUG: ETag is missing from response headers!');
            throw new Error('ETag missing from S3 upload response');
        }
        
        return etag;
    }

    async completeUpload(uploadId, key, bucket, parts) {
        console.log('🏁 DEBUG: === completeUpload STARTED ===');
        console.log('🏁 DEBUG: uploadId:', uploadId);
        console.log('🏁 DEBUG: key:', key);
        console.log('🏁 DEBUG: bucket:', bucket);
        console.log('🏁 DEBUG: parts count:', parts.length);
        console.log('🏁 DEBUG: parts data:', JSON.stringify(parts, null, 2));
        
        const requestBody = {
            uploadId,
            key,
            bucket,
            parts
        };
        
        console.log('🏁 DEBUG: Request body:', JSON.stringify(requestBody, null, 2));
        
        const response = await fetch(`${this.apiBaseUrl}upload/complete`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(requestBody)
        });

        console.log('🏁 DEBUG: Response status:', response.status, response.statusText);

        if (!response.ok) {
            const errorText = await response.text();
            console.error('🏁 DEBUG: Error response:', errorText);
            throw new Error(`Upload completion failed: ${response.statusText} - ${errorText}`);
        }

        const result = await response.json();
        console.log('🏁 DEBUG: Success response:', result);
        return result;
    }

    showProgress(text, percent) {
        this.progressText.textContent = text;
        this.progressPercent.textContent = `${percent}%`;
        this.progressFill.style.width = `${percent}%`;
        this.uploadProgress.style.display = 'block';
    }

    updateProgress(percent) {
        this.progressPercent.textContent = `${percent}%`;
        this.progressFill.style.width = `${percent}%`;
    }

    hideProgress() {
        this.uploadProgress.style.display = 'none';
    }

    enableChatIfReady() {
        if (this.apiBaseUrl && this.uploadedFiles.size > 0) {
            this.queryInput.disabled = false;
            this.sendButton.disabled = false;
            this.queryInput.placeholder = 'Ask a question about your documents...';
        }
    }

    async sendQuery() {
        const query = this.queryInput.value.trim();
        if (!query) return;

        this.addMessage(query, 'user');
        this.queryInput.value = '';
        this.queryInput.disabled = true;
        this.sendButton.disabled = true;

        const loadingMessage = this.addLoadingMessage();

        try {
            const response = await fetch(`${this.apiBaseUrl}chat`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ query })
            });

            if (!response.ok) {
                throw new Error(`Query failed: ${response.statusText}`);
            }

            const data = await response.json();
            this.removeLoadingMessage(loadingMessage);
            this.addMessage(data.answer, 'assistant', data.citations);

        } catch (error) {
            console.error('Query failed:', error);
            this.removeLoadingMessage(loadingMessage);
            this.addMessage('Sorry, I encountered an error while processing your question. Please try again.', 'assistant');
            this.showError(`Query failed: ${error.message}`);
        } finally {
            this.queryInput.disabled = false;
            this.sendButton.disabled = false;
            this.queryInput.focus();
        }
    }

    addMessage(content, sender, citations = null) {
        const messageDiv = document.createElement('div');
        messageDiv.className = `message ${sender}`;

        let citationsHtml = '';
        if (citations && citations.length > 0) {
            citationsHtml = `
                <div class="citations">
                    <h4>Sources (${citations.length}):</h4>
                    ${citations.map((citation, index) => 
                        `<div class="citation-item">Source ${index + 1} (Score: ${citation.score.toFixed(3)})</div>`
                    ).join('')}
                </div>
            `;
        }

        messageDiv.innerHTML = `
            <div class="message-content">
                ${content}
                ${citationsHtml}
            </div>
        `;

        this.chatMessages.appendChild(messageDiv);
        this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
        
        if (sender === 'user' && this.chatMessages.querySelector('.welcome-message')) {
            this.chatMessages.querySelector('.welcome-message').remove();
        }
    }

    addLoadingMessage() {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant';
        messageDiv.innerHTML = `
            <div class="message-loading">
                <span>Thinking</span>
                <div class="loading-dots">
                    <div class="loading-dot"></div>
                    <div class="loading-dot"></div>
                    <div class="loading-dot"></div>
                </div>
            </div>
        `;

        this.chatMessages.appendChild(messageDiv);
        this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
        return messageDiv;
    }

    removeLoadingMessage(messageDiv) {
        if (messageDiv && messageDiv.parentNode) {
            messageDiv.parentNode.removeChild(messageDiv);
        }
    }

    showError(message) {
        console.error('Showing error:', message);
        this.errorMessage.textContent = message;
        this.errorModal.style.display = 'flex';
        
        // Also show as notification if modal fails
        if ('Notification' in window && Notification.permission === 'granted') {
            new Notification('Upload Error', { body: message, icon: '❌' });
        }
    }

    showSuccess(message) {
        console.log('Success:', message);
        // Create success notification
        const notification = document.createElement('div');
        notification.style.cssText = `
            position: fixed; top: 20px; right: 20px; z-index: 10000;
            background: #d4edda; color: #155724; padding: 15px 20px;
            border: 1px solid #c3e6cb; border-radius: 8px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.1);
            font-weight: bold; max-width: 400px;
        `;
        notification.textContent = `✅ ${message}`;
        document.body.appendChild(notification);
        
        // Auto remove after 5 seconds
        setTimeout(() => {
            if (notification.parentNode) {
                notification.parentNode.removeChild(notification);
            }
        }, 5000);
        
        // Browser notification if permitted
        if ('Notification' in window && Notification.permission === 'granted') {
            new Notification('Upload Success', { body: message, icon: '✅' });
        }
    }

    showInfo(message) {
        console.info('Info:', message);
        const notification = document.createElement('div');
        notification.style.cssText = `
            position: fixed; top: 20px; right: 20px; z-index: 10000;
            background: #d1ecf1; color: #0c5460; padding: 15px 20px;
            border: 1px solid #bee5eb; border-radius: 8px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.1);
            max-width: 400px;
        `;
        notification.textContent = `ℹ️ ${message}`;
        document.body.appendChild(notification);
        
        setTimeout(() => {
            if (notification.parentNode) {
                notification.parentNode.removeChild(notification);
            }
        }, 3000);
    }

    hideError() {
        this.errorModal.style.display = 'none';
    }

    formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    }

    validateFileBeforeUpload(file) {
        const errors = [];
        
        // Check file size
        if (file.size > this.maxFileSize) {
            errors.push(`File size (${this.formatFileSize(file.size)}) exceeds the maximum limit of ${this.formatFileSize(this.maxFileSize)}`);
        }
        
        // Check file type
        const fileExtension = '.' + file.name.split('.').pop().toLowerCase();
        if (!this.supportedTypes.includes(fileExtension)) {
            errors.push(`File format not supported. Supported formats: ${this.supportedTypes.join(', ').toUpperCase()}`);
        }
        
        // Check if file is empty
        if (file.size === 0) {
            errors.push('File is empty');
        }
        
        return errors;
    }
}

// Global function for error modal
function hideError() {
    window.ragApp.hideError();
}

// Initialize the app when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    window.ragApp = new RAGApp();
});