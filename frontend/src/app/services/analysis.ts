import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class AnalysisService {
  private apiUrl = 'http://localhost:8080/documents/upload'; // replace with your endpoint

  constructor(private http: HttpClient) {}

  analyzeFile(file: any): Observable<any> {
    const formData = new FormData();
    console.log(file);
    formData.append('file', file, file.name); // key must match backend
    return this.http.post<any>(this.apiUrl, formData);
  }
}