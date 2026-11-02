import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export type DocumentStatus = 'PENDING' | 'EXTRACTING' | 'EXTRACTED' | 'FAILED';

export interface DocumentStatusResponse {
  id: string;
  status: DocumentStatus;
  pageCount?: number | null;
  characterCount?: number | null;
  errorCode?: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class AnalysisService {
  private apiUrl = 'http://localhost:8080/api/documents';
  constructor(private http: HttpClient) {}

  analyzeFile(file: any): Observable<any> {
    const formData = new FormData();
    formData.append('file', file, file.name); // key must match backend
    return this.http.post<any>(`${this.apiUrl}/upload`, formData);
  }

  getDocumentStatus(documentId: string, token: string): Observable<DocumentStatusResponse> {
    return this.http.get<DocumentStatusResponse>(`${this.apiUrl}/${encodeURIComponent(documentId)}`, {
      headers: { 'X-Document-Token': token },
    });
  }
}
