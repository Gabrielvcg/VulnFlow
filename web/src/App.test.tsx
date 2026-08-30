import {fireEvent,render,screen} from '@testing-library/react';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {MemoryRouter} from 'react-router-dom';
import {describe,expect,it} from 'vitest';
import App from './App';

describe('public case study',()=>{
  it('explains the value and labels its evidence as sanitized',()=>{
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/']}><App/></MemoryRouter></QueryClientProvider>);
    expect(screen.getByRole('heading',{name:/Vulnerability data/i})).toBeInTheDocument();
    expect(screen.getByText(/Historical sanitized data/i)).toBeInTheDocument();
    expect(screen.getAllByText('190')).toHaveLength(2);
  });

  it('plays the public replay without submitting a scan request',()=>{
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/']}><App/></MemoryRouter></QueryClientProvider>);
    fireEvent.click(screen.getAllByRole('button',{name:/Play public scan replay/i})[0]);
    expect(screen.getByText(/Replay running through the pipeline/i)).toBeInTheDocument();
    expect(screen.getAllByText('RUNNING').length).toBeGreaterThan(0);
  });
});
