import React, { useState, useEffect } from 'react';
import { apiClient } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Upload, FileText, Database, Trash2, Plus, Loader2, Check, X, AlertTriangle, Clock, CheckCircle, Eye, Copy, RefreshCw, Search } from 'lucide-react';
import { toast } from 'sonner';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";

interface Document {
  id: string;
  filename: string;
  bytes: number;
  created_at: number;
  purpose: string;
}

interface VectorStoreFile {
  id: string;
  vector_store_id: string;
  status: string;
  attributes: {
    filename: string;
  };
}

interface VectorStore {
  id: string;
  name: string;
  file_count: number;
  created_at: number;
  metadata: Record<string, any>;
}

interface DocumentManagerProps {
  apiKey: string;
  baseUrl: string;
  onVectorStoreSelect?: (vectorStoreId: string | null) => void;
  selectedVectorStore?: string;
}

interface DeleteDialogState {
  isOpen: boolean;
  type: 'file' | 'vectorStore' | 'fileFromVectorStore';
  itemId: string;
  vectorStoreId?: string;
  itemName: string;
}

const DocumentManager: React.FC<DocumentManagerProps> = ({
  apiKey,
  baseUrl,
  onVectorStoreSelect,
  selectedVectorStore
}) => {
  const [documents, setDocuments] = useState<Document[]>([]);
  const [vectorStores, setVectorStores] = useState<VectorStore[]>([]);
  const [vectorStoreFiles, setVectorStoreFiles] = useState<Record<string, VectorStoreFile[]>>({});
  const [isLoading, setIsLoading] = useState(false);
  const [newVectorStoreName, setNewVectorStoreName] = useState('');
  const [uploadingFile, setUploadingFile] = useState(false);
  const [deleteDialog, setDeleteDialog] = useState<DeleteDialogState>({
    isOpen: false,
    type: 'file',
    itemId: '',
    itemName: ''
  });
  const [showCreateVectorStore, setShowCreateVectorStore] = useState(false);
  const [newVectorStoreExpires, setNewVectorStoreExpires] = useState('7');
  const [newVectorStoreDescription, setNewVectorStoreDescription] = useState('');
  const [viewingFile, setViewingFile] = useState<string | null>(null);
  const [fileContent, setFileContent] = useState<string>('');
  const [loadingFileContent, setLoadingFileContent] = useState(false);
  const [openDropdown, setOpenDropdown] = useState<string | null>(null);

  useEffect(() => {
    if (apiKey && baseUrl) {
      loadDocuments();
      loadVectorStores();
    }
  }, [apiKey, baseUrl]);

  useEffect(() => {
    // Load files for each vector store
    if (vectorStores.length > 0 && apiKey && baseUrl) {
      Promise.all(vectorStores.map(store => loadVectorStoreFiles(store.id)));
    }
  }, [vectorStores, apiKey, baseUrl]);

  // Auto-refresh vector store files every 3 seconds to update processing status
  useEffect(() => {
    if (!apiKey || !baseUrl || vectorStores.length === 0) return;

    const interval = setInterval(() => {
      // Check if any vector store has files in progress
      const hasProcessingFiles = Object.values(vectorStoreFiles).some(files =>
        files.some(file => file.status === 'in_progress')
      );

      if (hasProcessingFiles) {
        // Refresh files for all vector stores that have processing files
        Object.entries(vectorStoreFiles).forEach(([vectorStoreId, files]) => {
          if (files.some(file => file.status === 'in_progress')) {
            loadVectorStoreFiles(vectorStoreId);
          }
        });
      }
    }, 3000); // Refresh every 3 seconds

    return () => clearInterval(interval);
  }, [apiKey, baseUrl, vectorStores, vectorStoreFiles]);

  const loadVectorStoreFiles = async (vectorStoreId: string) => {
    try {
      const response = await apiClient.rawRequest(`/v1/vector_stores/${vectorStoreId}/files`, {
        headers: {
          'Authorization': `Bearer ${apiKey}`,
        },
      });

      if (response.ok) {
        const data = await response.json();
        const allFiles = data.data || [];
        // Include both completed and in_progress files
        const validFiles = allFiles.filter((file: VectorStoreFile) => 
          file.status === 'completed' || file.status === 'in_progress'
        );
        
        setVectorStoreFiles(prev => ({
          ...prev,
          [vectorStoreId]: validFiles
        }));
      }
    } catch (error) {
      console.error(`Error loading files for vector store ${vectorStoreId}:`, error);
      // Authentication errors are handled by ApiClient
    }
  };

  const loadDocuments = async () => {
    try {
      const response = await apiClient.rawRequest('/v1/files', {
        headers: {
          'Authorization': `Bearer ${apiKey}`,
        },
      });

      if (response.ok) {
        const data = await response.json();
        setDocuments(data.data || []);
      }
    } catch (error) {
      console.error('Error loading documents:', error);
      // Authentication errors are handled by ApiClient
    }
  };

  const loadVectorStores = async () => {
    try {
      const response = await apiClient.rawRequest('/v1/vector_stores', {
        headers: {
          'Authorization': `Bearer ${apiKey}`,
        },
      });

      if (response.ok) {
        const data = await response.json();
        setVectorStores(data.data || []);
      }
    } catch (error) {
      console.error('Error loading vector stores:', error);
      // Authentication errors are handled by ApiClient
    }
  };

  const uploadDocument = async (file: File) => {
    if (!apiKey.trim()) {
      toast.error('Please set your API key first');
      return;
    }

    setUploadingFile(true);

    try {
      const formData = new FormData();
      formData.append('file', file);
      formData.append('purpose', 'user_data');

      const response = await apiClient.rawRequest('/v1/files', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${apiKey}`,
        },
        body: formData,
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setDocuments(prev => [...prev, data]);
      toast.success(`Document "${file.name}" uploaded successfully!`);
    } catch (error) {
      console.error('Error uploading document:', error);
      toast.error('Failed to upload document. Please try again.');
    } finally {
      setUploadingFile(false);
    }
  };

  const createVectorStore = async () => {
    if (!newVectorStoreName.trim()) {
      toast.error('Please enter a vector store name');
      return;
    }

    if (!apiKey.trim()) {
      toast.error('Please set your API key first');
      return;
    }

    setIsLoading(true);

    try {
      const response = await apiClient.rawRequest('/v1/vector_stores', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${apiKey}`,
        },
        body: JSON.stringify({
          name: newVectorStoreName.trim(),
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setVectorStores(prev => [...prev, data]);
      setNewVectorStoreName('');
      
      // Auto-select the newly created vector store
      onVectorStoreSelect?.(data.id);
      
      toast.success(`Vector store "${data.name}" created successfully!`);
    } catch (error) {
      console.error('Error creating vector store:', error);
      toast.error('Failed to create vector store. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  const addFileToVectorStore = async (fileId: string, vectorStoreId: string) => {
    if (!apiKey.trim()) {
      toast.error('Please set your API key first');
      return;
    }

    setIsLoading(true);

    try {
      const response = await apiClient.rawRequest(`/v1/vector_stores/${vectorStoreId}/files`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${apiKey}`,
        },
        body: JSON.stringify({
          file_id: fileId,
          chunking_strategy: {
            type: "static",
            static: {
              max_chunk_size_tokens: 1000,
              chunk_overlap_tokens: 200
            }
          },
          attributes: {
            category: "user_document",
            language: "en"
          }
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      // Refresh data from API
      await loadVectorStores();
      await loadVectorStoreFiles(vectorStoreId);
      
      toast.success('Document added to vector store successfully!');
    } catch (error) {
      console.error('Error adding file to vector store:', error);
      toast.error('Failed to add document to vector store. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  const removeFileFromVectorStore = async (fileId: string, vectorStoreId: string) => {
    if (!apiKey.trim()) {
      toast.error('Please set your API key first');
      return;
    }

    setIsLoading(true);

    try {
      const response = await apiClient.rawRequest(`/v1/vector_stores/${vectorStoreId}/files/${fileId}`, {
        method: 'DELETE',
        headers: {
          'Authorization': `Bearer ${apiKey}`,
        },
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      // Refresh data from API
      await loadVectorStores();
      await loadVectorStoreFiles(vectorStoreId);
      
      toast.success('File removed from vector store successfully!');
    } catch (error) {
      console.error('Error removing file from vector store:', error);
      toast.error('Failed to remove file from vector store. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  const deleteFile = async (fileId: string) => {
    if (!apiKey.trim()) {
      toast.error('Please set your API key first');
      return;
    }

    setIsLoading(true);

    try {
      const response = await apiClient.rawRequest(`/v1/files/${fileId}`, {
        method: 'DELETE',
        headers: {
          'Authorization': `Bearer ${apiKey}`,
        },
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      // Refresh all data from API
      await loadDocuments();
      await loadVectorStores();
      // Refresh vector store files for all stores
      for (const store of vectorStores) {
        await loadVectorStoreFiles(store.id);
      }
      
      toast.success('File deleted successfully!');
    } catch (error) {
      console.error('Error deleting file:', error);
      toast.error('Failed to delete file. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  const deleteVectorStore = async (vectorStoreId: string) => {
    if (!apiKey.trim()) {
      toast.error('Please set your API key first');
      return;
    }

    setIsLoading(true);

    try {
      const response = await apiClient.rawRequest(`/v1/vector_stores/${vectorStoreId}`, {
        method: 'DELETE',
        headers: {
          'Authorization': `Bearer ${apiKey}`,
        },
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      // If the deleted store was selected, deselect it
      if (selectedVectorStore === vectorStoreId) {
        onVectorStoreSelect?.(null);
      }

      // Refresh data from API
      await loadVectorStores();
      
      toast.success('Vector store deleted successfully!');
    } catch (error) {
      console.error('Error deleting vector store:', error);
      toast.error('Failed to delete vector store. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleDeleteConfirm = () => {
    const { type, itemId, vectorStoreId } = deleteDialog;
    
    if (type === 'file') {
      deleteFile(itemId);
    } else if (type === 'vectorStore') {
      deleteVectorStore(itemId);
    } else if (type === 'fileFromVectorStore' && vectorStoreId) {
      removeFileFromVectorStore(itemId, vectorStoreId);
    }
    
    setDeleteDialog({ ...deleteDialog, isOpen: false });
  };

  const handleFileUpload = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      uploadDocument(file);
    }
  };

  const formatFileSize = (bytes: number) => {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const isFileInVectorStore = (fileId: string, vectorStoreId: string): boolean => {
    const files = vectorStoreFiles[vectorStoreId] || [];
    return files.some(file => file.id.includes(fileId));
  };

  const viewFileContent = async (fileId: string) => {
    setViewingFile(fileId);
    setLoadingFileContent(true);
    setFileContent('');

    try {
      const response = await apiClient.rawRequest(`/v1/files/${fileId}/content`, {
        headers: {
          'Authorization': `Bearer ${apiKey}`,
        },
      });

      if (response.ok) {
        const content = await response.text();
        setFileContent(content);
      } else {
        setFileContent('Failed to load file content');
      }
    } catch (error) {
      console.error('Error loading file content:', error);
      setFileContent('Error loading file content');
    } finally {
      setLoadingFileContent(false);
    }
  };

  return (
    <div className="space-y-8">
      {/* Header Section with Geist UI styling */}
      <div className="bg-gradient-to-r from-primary/5 via-primary/10 to-success/5 dark:from-primary/10 dark:via-primary/20 dark:to-success/10 p-6 rounded-xl border border-primary/20 dark:border-primary/30">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-4">
            <div className="w-12 h-12 bg-primary/10 dark:bg-primary/20 rounded-lg flex items-center justify-center">
              <Database className="h-6 w-6 text-primary dark:text-primary-light" />
            </div>
          <div>
              <h2 className="text-xl font-semibold text-foreground">Document Library</h2>
              <p className="text-sm text-muted-foreground mt-1">
                Upload files and organize them into searchable vector stores
              </p>
              </div>
          </div>
          <div className="flex items-center space-x-3">
            <Button 
              onClick={loadVectorStores}
              variant="outline"
              size="sm"
              disabled={isLoading}
              className="border-border text-foreground hover:bg-accent"
            >
              {isLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
              Refresh
            </Button>
          </div>
        </div>

        {/* Quick Stats - New Geist UI section */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mt-6">
          <div className="bg-background/50 backdrop-blur-sm rounded-lg p-4 border border-border/50">
            <div className="flex items-center space-x-3">
              <FileText className="h-5 w-5 text-primary dark:text-primary-light" />
              <div>
                <p className="text-xs font-medium text-muted-foreground">Files Uploaded</p>
                <p className="text-lg font-semibold text-foreground">{documents.length}</p>
              </div>
            </div>
          </div>
          <div className="bg-background/50 backdrop-blur-sm rounded-lg p-4 border border-border/50">
            <div className="flex items-center space-x-3">
              <Database className="h-5 w-5 text-success dark:text-success-light" />
              <div>
                <p className="text-xs font-medium text-muted-foreground">Vector Stores</p>
                <p className="text-lg font-semibold text-foreground">{vectorStores.length}</p>
              </div>
            </div>
          </div>
          <div className="bg-background/50 backdrop-blur-sm rounded-lg p-4 border border-border/50">
            <div className="flex items-center space-x-3">
              <Search className="h-5 w-5 text-warning dark:text-warning-light" />
              <div>
                <p className="text-xs font-medium text-muted-foreground">Active Store</p>
                <p className="text-sm font-medium text-foreground truncate">
                  {selectedVectorStore ? vectorStores.find(vs => vs.id === selectedVectorStore)?.name || 'Unknown' : 'None'}
                  </p>
                </div>
            </div>
          </div>
        </div>
      </div>

      {/* File Upload Section - Redesigned with Geist UI */}
      <Card className="p-6 bg-card border border-border">
        <div className="flex items-center space-x-3 mb-6">
          <div className="w-8 h-8 bg-warning/10 dark:bg-warning/20 rounded-lg flex items-center justify-center">
            <Upload className="h-4 w-4 text-warning dark:text-warning-light" />
          </div>
          <h3 className="text-lg font-semibold text-foreground">Upload Documents</h3>
                    </div>

        <div className="space-y-6">
          <div className="border-2 border-dashed border-border rounded-lg p-6 hover:border-primary/50 dark:hover:border-primary/50 transition-colors duration-200">
            <div className="text-center">
              <Upload className="mx-auto h-8 w-8 text-muted-foreground mb-3" />
              <h4 className="text-sm font-medium text-foreground mb-2">Choose files to upload</h4>
              <p className="text-xs text-muted-foreground mb-4">
                Supports PDF, TXT, DOCX, MD and other text formats
              </p>
              <label className="inline-block">
                <input
                  type="file"
                  multiple
                  onChange={handleFileUpload}
                  className="hidden"
                  accept=".pdf,.txt,.docx,.md,.json"
                />
                <span className="inline-flex items-center justify-center h-10 px-4 py-2 bg-primary hover:bg-primary/90 text-primary-foreground font-medium cursor-pointer rounded-lg transition-colors disabled:opacity-50 dark:bg-primary dark:hover:bg-primary/90 dark:text-primary-foreground">
                  {uploadingFile ? (
                    <>
                      <Loader2 className="h-4 w-4 animate-spin mr-2" />
                      Uploading...
                    </>
                  ) : (
                    <>
                      <Plus className="h-4 w-4 mr-2" />
                      Select Files
                    </>
                  )}
                </span>
              </label>
                </div>
              </div>

          {/* File List with enhanced Geist UI styling */}
          {documents.length > 0 && (
            <div className="space-y-3">
              <h4 className="text-sm font-medium text-foreground flex items-center">
                <FileText className="h-4 w-4 mr-2" />
                Uploaded Files ({documents.length})
              </h4>
              <div className="max-h-48 overflow-y-auto space-y-2 border border-border rounded-lg p-3 bg-muted">
                {documents.map((file) => (
                  <div key={file.id} className="flex items-center justify-between p-3 bg-card rounded-lg border border-border hover:shadow-sm transition-shadow">
                    <div className="flex items-center space-x-3 flex-1 min-w-0">
                      <FileText className="h-4 w-4 text-primary dark:text-primary-light flex-shrink-0" />
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-medium text-foreground truncate">{file.filename}</p>
                        <div className="flex items-center space-x-4 mt-1">
                          <span className="text-xs text-muted-foreground">
                            {Math.round(file.bytes / 1024)} KB
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {file.created_at ? new Date(file.created_at * 1000).toLocaleDateString() : 'Unknown date'}
                          </span>
                        </div>
                      </div>
                    </div>
                    <div className="flex items-center space-x-2 flex-shrink-0">
                      {/* Add to Vector Store Dropdown */}
                      {vectorStores.length > 0 && (
                        <div className="relative">
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={(e) => {
                              e.stopPropagation();
                              setOpenDropdown(openDropdown === file.id ? null : file.id);
                            }}
                            className="border-success/30 dark:border-success/40 text-success dark:text-success-light hover:bg-success/5 dark:hover:bg-success/10"
                            title="Add to vector store"
                          >
                            <Database className="h-3 w-3 mr-1" />
                            Add to Store
                          </Button>
                        </div>
                      )}
                      
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => viewFileContent(file.id)}
                        className="border-border text-foreground hover:bg-accent"
                        title="View content"
                      >
                        <Eye className="h-3 w-3" />
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                          onClick={() => setDeleteDialog({
                            isOpen: true,
                          type: 'file',
                            itemId: file.id,
                          itemName: file.filename
                          })}
                        className="border-error/30 dark:border-error/40 text-error dark:text-error-light hover:bg-error/5 dark:hover:bg-error/10"
                        title="Delete file"
                        >
                        <Trash2 className="h-3 w-3" />
                        </Button>
                      </div>
                  </div>
                ))}
                </div>
            </div>
          )}
        </div>
      </Card>

      {/* Vector Store Management - Redesigned with Geist UI */}
      <Card className="p-6 bg-card border border-border">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center space-x-3">
            <div className="w-8 h-8 bg-success/10 dark:bg-success/20 rounded-lg flex items-center justify-center">
              <Database className="h-4 w-4 text-success dark:text-success-light" />
            </div>
            <h3 className="text-lg font-semibold text-foreground">Vector Stores</h3>
          </div>
          <Button
            onClick={() => setShowCreateVectorStore(true)}
            className="bg-success hover:bg-success-light text-white font-medium"
            disabled={documents.length === 0}
          >
            <Plus className="h-4 w-4 mr-2" />
            Create Store
          </Button>
        </div>

        {/* Create Vector Store Form */}
        {showCreateVectorStore && (
          <div className="mb-6 p-6 bg-muted border border-border rounded-lg">
            <div className="flex items-center space-x-3 mb-4">
              <div className="w-6 h-6 bg-primary/10 dark:bg-primary/20 rounded-full flex items-center justify-center">
                <Plus className="h-3 w-3 text-primary dark:text-primary-light" />
              </div>
              <h4 className="text-base font-semibold text-foreground">Create New Vector Store</h4>
            </div>

            <div className="space-y-4">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="vectorStoreName" className="text-sm font-medium text-foreground">Store Name</Label>
                  <Input
                    id="vectorStoreName"
                    type="text"
                    placeholder="My Document Store"
                    value={newVectorStoreName}
                    onChange={(e) => setNewVectorStoreName(e.target.value)}
                    className="bg-card border border-border text-foreground placeholder:text-muted-foreground focus:border-primary dark:focus:border-primary-light"
                  />
                </div>
                <div className="space-y-2">
                  <Label className="text-sm font-medium text-foreground">Expiration</Label>
                  <select
                    value={newVectorStoreExpires}
                    onChange={(e) => setNewVectorStoreExpires(e.target.value)}
                    className="w-full px-3 py-2 bg-card border border-border text-foreground rounded-lg focus:border-primary dark:focus:border-primary-light focus:ring-1 focus:ring-primary/20 dark:focus:ring-primary/30"
                  >
                    <option value="1">1 day</option>
                    <option value="7">7 days</option>
                    <option value="30">30 days</option>
                  </select>
                </div>
              </div>
        
        <div className="space-y-2">
                <Label htmlFor="vectorStoreDesc" className="text-sm font-medium text-foreground">Description (Optional)</Label>
                <Textarea
                  id="vectorStoreDesc"
                  placeholder="Describe what documents this store contains..."
                  value={newVectorStoreDescription}
                  onChange={(e) => setNewVectorStoreDescription(e.target.value)}
                  rows={3}
                  className="resize-none bg-card border border-border text-foreground placeholder:text-muted-foreground focus:border-primary dark:focus:border-primary-light"
                />
              </div>

              <div className="flex items-center justify-end space-x-3 pt-4 border-t border-border">
                <Button
                  variant="outline"
                  onClick={() => {
                    setShowCreateVectorStore(false);
                    setNewVectorStoreName('');
                    setNewVectorStoreDescription('');
                  }}
                  className="border-border text-foreground hover:bg-accent"
                >
                  Cancel
                </Button>
                <Button
                  onClick={createVectorStore}
                  disabled={!newVectorStoreName.trim() || isLoading}
                  className="bg-primary hover:bg-primary/90 text-primary-foreground font-medium dark:bg-primary dark:hover:bg-primary/90 dark:text-primary-foreground"
                >
                  {isLoading ? (
                    <>
                      <Loader2 className="h-4 w-4 animate-spin mr-2" />
                      Creating...
                    </>
                  ) : (
                    <>
                      <CheckCircle className="h-4 w-4 mr-2" />
                      Create Store
                    </>
                  )}
                </Button>
              </div>
            </div>
          </div>
        )}

        {/* Vector Store List */}
        <div className="space-y-3">
          {vectorStores.length === 0 ? (
            <div className="text-center py-12 border-2 border-dashed border-border rounded-lg">
              <Database className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
              <h4 className="text-sm font-medium text-foreground mb-2">No vector stores yet</h4>
              <p className="text-xs text-muted-foreground mb-4">
                Create your first vector store to organize and search through your documents
              </p>
              <Button
                onClick={() => setShowCreateVectorStore(true)}
                disabled={documents.length === 0}
                className="bg-success hover:bg-success/90 text-white font-medium dark:bg-success dark:hover:bg-success/90 dark:text-white"
              >
                <Plus className="h-4 w-4 mr-2" />
                Create Your First Store
              </Button>
            </div>
          ) : (
            vectorStores.map((store) => (
              <div key={store.id} className={`p-4 rounded-lg border transition-all duration-200 ${
                selectedVectorStore === store.id
                  ? 'border-success bg-success/5 dark:bg-success/10 shadow-sm'
                  : 'border-border bg-card hover:shadow-sm'
              }`}>
                <div className="flex items-start justify-between">
                  <div className="flex items-center space-x-3 flex-1 min-w-0">
                    <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${
                      selectedVectorStore === store.id
                        ? 'bg-success/10 dark:bg-success/20'
                        : 'bg-muted'
                    }`}>
                      <Database className={`h-5 w-5 ${
                        selectedVectorStore === store.id
                          ? 'text-success dark:text-success-light'
                          : 'text-muted-foreground'
                      }`} />
                    </div>
                    <div className="min-w-0 flex-1">
                      <h4 className="text-base font-semibold text-foreground truncate">{store.name}</h4>
                      {store.metadata?.description && (
                        <p className="text-sm text-muted-foreground mt-1 line-clamp-2">{store.metadata.description}</p>
                      )}
                      <div className="flex items-center space-x-4 mt-2">
                        <span className="text-xs text-muted-foreground flex items-center">
                          <FileText className="h-3 w-3 mr-1" />
                          {vectorStoreFiles[store.id]?.length || 0} files
                        </span>
                        <span className="text-xs text-muted-foreground flex items-center">
                          <Clock className="h-3 w-3 mr-1" />
                          Created {store.created_at ? new Date(store.created_at * 1000).toLocaleDateString() : 'Unknown'}
                        </span>
                        {selectedVectorStore === store.id && (
                          <span className="text-xs font-medium text-success dark:text-success-light bg-success/10 dark:bg-success/20 px-2 py-1 rounded-full flex items-center">
                            <CheckCircle className="h-3 w-3 mr-1" />
                            Active
                          </span>
                        )}
                      </div>
                    </div>
                  </div>
                  <div className="flex items-center space-x-2 flex-shrink-0">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        navigator.clipboard.writeText(store.id);
                        toast.success('Store ID copied to clipboard');
                      }}
                      className="border-border text-foreground hover:bg-accent"
                      title="Copy ID"
                    >
                      <Copy className="h-3 w-3" />
                    </Button>
                    <Button
                      variant={selectedVectorStore === store.id ? "outline" : "default"}
                      size="sm"
                      onClick={() => onVectorStoreSelect && onVectorStoreSelect(selectedVectorStore === store.id ? '' : store.id)}
                      className={selectedVectorStore === store.id 
                        ? "border-success text-success dark:text-success-light hover:bg-success/5 dark:hover:bg-success/10"
                        : "bg-primary hover:bg-primary/90 text-primary-foreground dark:bg-primary dark:hover:bg-primary/90 dark:text-primary-foreground"
                      }
                    >
                      {selectedVectorStore === store.id ? (
                        <>
                          <X className="h-3 w-3 mr-1" />
                          Deselect
                        </>
                      ) : (
                        <>
                          <Check className="h-3 w-3 mr-1" />
                          Select
                        </>
                      )}
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => deleteVectorStore(store.id)}
                      className="border-error/30 dark:border-error/40 text-error dark:text-error-light hover:bg-error/5 dark:hover:bg-error/10"
                      title="Delete store"
                    >
                      <Trash2 className="h-3 w-3" />
                    </Button>
                  </div>
                </div>
                
                {/* Vector Store Files List */}
                {vectorStoreFiles[store.id] && vectorStoreFiles[store.id].length > 0 && (
                  <div className="mt-4 pt-4 border-t border-border">
                    <div className="flex items-center justify-between mb-3">
                      <h5 className="text-sm font-medium text-foreground flex items-center">
                        <FileText className="h-4 w-4 mr-2" />
                        Files in Store ({vectorStoreFiles[store.id].length})
                      </h5>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => loadVectorStoreFiles(store.id)}
                        className="border-border text-foreground hover:bg-accent"
                        title="Refresh files"
                      >
                        <RefreshCw className="h-3 w-3" />
                      </Button>
                    </div>
                    <div className="space-y-2 max-h-32 overflow-y-auto">
                      {vectorStoreFiles[store.id].map((file) => {
                        const originalFile = documents.find(doc => file.id.includes(doc.id));
                        const filename = file.attributes?.filename || originalFile?.filename || 'Unknown file';
                        
                        return (
                          <div key={file.id} className="flex items-center justify-between p-2 bg-muted rounded-lg border border-border">
                            <div className="flex items-center space-x-2 flex-1 min-w-0">
                              <div className={`w-2 h-2 rounded-full ${
                                file.status === 'completed' ? 'bg-success' : 
                                file.status === 'in_progress' ? 'bg-warning animate-pulse' : 
                                'bg-error'
                              }`} />
                              <FileText className="h-3 w-3 text-muted-foreground flex-shrink-0" />
                              <span className="text-xs font-medium text-foreground truncate">{filename}</span>
                              <span className={`text-xs px-2 py-0.5 rounded-full ${
                                file.status === 'completed' ? 'bg-success/10 text-success dark:text-success-light' :
                                file.status === 'in_progress' ? 'bg-warning/10 text-warning dark:text-warning-light' :
                                'bg-error/10 text-error dark:text-error-light'
                              }`}>
                                {file.status === 'in_progress' ? 'Processing...' : 
                                 file.status === 'completed' ? 'Ready' : 
                                 file.status}
                              </span>
                            </div>
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => setDeleteDialog({
                                isOpen: true,
                                type: 'fileFromVectorStore',
                                itemId: file.id,
                                vectorStoreId: store.id,
                                itemName: filename
                              })}
                              className="border-error/30 dark:border-error/40 text-error dark:text-error-light hover:bg-error/5 dark:hover:bg-error/10"
                              title="Remove from store"
                            >
                              <X className="h-3 w-3" />
                            </Button>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            ))
          )}
        </div>
      </Card>

      {/* File Content Modal - Enhanced with Geist UI */}
      {viewingFile && (
        <div className="fixed inset-0 bg-black/50 dark:bg-black/70 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-card rounded-xl shadow-xl max-w-4xl w-full max-h-[80vh] flex flex-col border border-border">
            <div className="flex items-center justify-between p-6 border-b border-border">
              <div className="flex items-center space-x-3">
                <div className="w-8 h-8 bg-primary/10 dark:bg-primary/20 rounded-lg flex items-center justify-center">
                  <Eye className="h-4 w-4 text-primary dark:text-primary-light" />
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-foreground">File Content</h3>
                  <p className="text-sm text-muted-foreground">
                    {documents.find(f => f.id === viewingFile)?.filename || 'Unknown file'}
                  </p>
                </div>
              </div>
                      <Button
                variant="outline"
                        size="sm"
                onClick={() => setViewingFile(null)}
                className="border-border text-foreground hover:bg-accent"
              >
                <X className="h-4 w-4" />
                      </Button>
            </div>
            <div className="flex-1 overflow-y-auto p-6">
              {loadingFileContent ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="h-8 w-8 animate-spin text-primary dark:text-primary-light" />
                </div>
              ) : (
                <pre className="whitespace-pre-wrap text-sm font-mono text-foreground bg-muted p-4 rounded-lg border border-border max-h-96 overflow-y-auto">
                  {fileContent || 'No content available'}
                </pre>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Add to Vector Store Dropdown Portal */}
      {openDropdown && (
        <>
          {/* Backdrop to close dropdown */}
          <div 
            className="fixed inset-0 z-40" 
            onClick={() => setOpenDropdown(null)}
          />
          {/* Dropdown menu - Large Modal Style */}
          <div className="fixed z-50 bg-card border border-border rounded-xl shadow-2xl w-96 max-w-[90vw]" 
               style={{
                 top: '50%',
                 left: '50%',
                 transform: 'translate(-50%, -50%)'
               }}>
            {/* Header */}
            <div className="flex items-center justify-between p-6 border-b border-border">
              <div className="flex items-center space-x-3">
                <div className="w-10 h-10 bg-success/10 dark:bg-success/20 rounded-lg flex items-center justify-center">
                  <Database className="h-5 w-5 text-success dark:text-success-light" />
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-foreground">Add to Vector Store</h3>
                  <p className="text-sm text-muted-foreground">
                    Select a store to add "{documents.find(f => f.id === openDropdown)?.filename || 'this file'}"
                  </p>
                </div>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setOpenDropdown(null)}
                className="border-border text-foreground hover:bg-accent"
              >
                <X className="h-4 w-4" />
              </Button>
            </div>
            
            {/* Content */}
            <div className="p-6 space-y-3 max-h-64 overflow-y-auto">
              {vectorStores.map((store) => {
                const isInStore = isFileInVectorStore(openDropdown, store.id);
                return (
                  <button
                    key={store.id}
                    onClick={() => {
                      if (!isInStore) {
                        addFileToVectorStore(openDropdown, store.id);
                        setOpenDropdown(null);
                      }
                    }}
                    disabled={isInStore || isLoading}
                    className={`w-full text-left p-4 rounded-lg border transition-all duration-200 ${
                      isInStore
                        ? 'bg-success/5 border-success/20 cursor-not-allowed'
                        : 'hover:bg-accent border-border hover:border-success/50 hover:shadow-sm'
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <div className="flex items-center space-x-3 flex-1 min-w-0">
                        <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${
                          isInStore ? 'bg-success/10 dark:bg-success/20' : 'bg-muted'
                        }`}>
                          <Database className={`h-4 w-4 ${
                            isInStore ? 'text-success dark:text-success-light' : 'text-muted-foreground'
                          }`} />
                        </div>
                        <div className="min-w-0 flex-1">
                          <p className="text-sm font-medium text-foreground truncate">{store.name}</p>
                          <p className="text-xs text-muted-foreground">
                            {vectorStoreFiles[store.id]?.length || 0} files • Created {store.created_at ? new Date(store.created_at * 1000).toLocaleDateString() : 'Unknown'}
                          </p>
                        </div>
                      </div>
                      {isInStore ? (
                        <div className="flex items-center space-x-2 text-success dark:text-success-light">
                          <Check className="h-4 w-4" />
                          <span className="text-xs font-medium">Added</span>
                        </div>
                      ) : (
                        <Plus className="h-4 w-4 text-muted-foreground" />
                      )}
                    </div>
                  </button>
                );
              })}
            </div>
            
            {/* Footer */}
            <div className="flex items-center justify-end space-x-3 p-6 border-t border-border bg-muted/30">
              <Button
                variant="outline"
                onClick={() => setOpenDropdown(null)}
                className="border-border text-foreground hover:bg-accent"
              >
                Cancel
              </Button>
            </div>
          </div>
        </>
      )}

      {/* Delete Confirmation Dialog - Enhanced with Geist UI */}
      <AlertDialog open={deleteDialog.isOpen} onOpenChange={(open) => setDeleteDialog({ ...deleteDialog, isOpen: open })}>
                      <AlertDialogContent className="bg-card/80 backdrop-blur-md border border-border">
          <AlertDialogHeader>
            <div className="flex items-center space-x-3 mb-4">
              <div className="w-10 h-10 bg-error/10 dark:bg-error/20 rounded-lg flex items-center justify-center">
                <AlertTriangle className="h-5 w-5 text-error dark:text-error-light" />
              </div>
              <div>
                <AlertDialogTitle className="text-lg font-semibold text-foreground">
                  Confirm Deletion
                </AlertDialogTitle>
                <AlertDialogDescription className="text-sm text-muted-foreground mt-1">
                  This action cannot be undone
                </AlertDialogDescription>
              </div>
            </div>
          </AlertDialogHeader>
          <div className="py-4">
            <p className="text-sm text-foreground">
              {deleteDialog.type === 'file' 
                ? 'Are you sure you want to delete this file? It will be removed from all vector stores.'
                : 'Are you sure you want to delete this vector store? All associated file associations will be lost.'
              }
            </p>
          </div>
          <AlertDialogFooter className="flex gap-3">
            <AlertDialogCancel className="border-border text-foreground hover:bg-accent">
              Cancel
            </AlertDialogCancel>
            <AlertDialogAction 
              onClick={handleDeleteConfirm}
              className="bg-error hover:bg-error-light text-white font-medium"
              disabled={isLoading}
            >
              {isLoading ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin mr-2" />
                  Deleting...
                </>
              ) : (
                <>
                  <Trash2 className="h-4 w-4 mr-2" />
                  Delete {deleteDialog.type === 'file' ? 'File' : 'Store'}
                </>
          )}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default DocumentManager; 