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
        this.uploadedFiles = document.getElementById('uploadedFiles');
        this.chatMessages = document.getElementById('chatMessages');
        this.queryInput = document.getElementById('queryInput');
        this.sendButton = document.getElementById('sendButton');
        this.errorModal = document.getElementById('errorModal');
        this.errorMessage = document.getElementById('errorMessage');
    }

    setupEventListeners() {
        // File upload events
        this.uploadArea.addEventListener('click', () => this.fileInput.click());
        this.fileInput.addEventListener('change', (e) => this.handleFileSelection(e.target.files));
        
        // Drag and drop events
        this.uploadArea.addEventListener('dragover', (e) => {
            e.preventDefault();
            this.uploadArea.classList.add('dragover');
        });
        
        this.uploadArea.addEventListener('dragleave', () => {
            this.uploadArea.classList.remove('dragover');
        });
        
        this.uploadArea.addEventListener('drop', (e) => {
            e.preventDefault();
            this.uploadArea.classList.remove('dragover');
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
            const response = await fetch('/config.json');
            if (response.ok) {
                const config = await response.json();
                this.apiBaseUrl = config.apiBaseUrl;
            } else {
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
        }
    }

    async handleFileSelection(files) {
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
        }
        
        // Upload valid files
        for (const file of filesToUpload) {
            this.addFileToList(file.name, 'uploading');
            await this.uploadFile(file);
        }
    }

    addFileToList(fileName, status) {
        const fileItem = document.createElement('div');
        fileItem.className = 'file-item';
        fileItem.innerHTML = `
            <span class="file-name">${fileName}</span>
            <span class="file-status ${status}">${this.getStatusText(status)}</span>
        `;
        this.uploadedFiles.appendChild(fileItem);
        return fileItem;
    }

    updateFileStatus(fileName, status) {
        const fileItems = this.uploadedFiles.querySelectorAll('.file-item');
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
            this.showProgress(`Uploading ${file.name} (${this.formatFileSize(file.size)})...`, 0);
            
            const { uploadId, key, bucket } = await this.initiateUpload(file);
            const parts = await this.uploadParts(file, uploadId, key, bucket);
            await this.completeUpload(uploadId, key, bucket, parts);
            
            this.uploadedFiles.set(file.name, { key, bucket });
            this.updateFileStatus(file.name, 'processing');
            this.hideProgress();
            
            // Real document processing happens on the backend, so give it more time
            setTimeout(() => {
                this.updateFileStatus(file.name, 'completed');
                this.enableChatIfReady();
            }, 5000);
            
        } catch (error) {
            console.error('Upload failed:', error);
            this.updateFileStatus(file.name, 'error');
            this.hideProgress();
            this.showError(`Failed to upload ${file.name}: ${error.message}`);
        }
    }

    async initiateUpload(file) {
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

        if (!response.ok) {
            throw new Error(`Upload initiation failed: ${response.statusText}`);
        }

        return await response.json();
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
        const response = await fetch(presignedUrl, {
            method: 'PUT',
            body: chunk
        });

        if (!response.ok) {
            throw new Error(`Chunk upload failed: ${response.statusText}`);
        }

        return response.headers.get('ETag');
    }

    async completeUpload(uploadId, key, bucket, parts) {
        const response = await fetch(`${this.apiBaseUrl}upload/complete`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                uploadId,
                key,
                bucket,
                parts
            })
        });

        if (!response.ok) {
            throw new Error(`Upload completion failed: ${response.statusText}`);
        }

        return await response.json();
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
            const response = await fetch(`${this.apiBaseUrl}query`, {
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
        this.errorMessage.textContent = message;
        this.errorModal.style.display = 'flex';
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