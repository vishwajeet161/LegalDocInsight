import { Routes } from '@angular/router';
import { Landing } from './pages/landing/landing';
import { Upload } from './pages/upload/upload';

export const routes: Routes = [
  { path: '', component: Landing },
  { path: 'upload', component: Upload },
];
