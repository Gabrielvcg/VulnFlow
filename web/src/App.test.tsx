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
    expect(screen.getAllByText('190')).toHaveLength(1);
    expect(screen.getByText(/Sanitized fixture manifest/i)).toBeInTheDocument();
    expect(screen.getByText('req_demo_01HZX7K4')).toBeInTheDocument();
    expect(screen.getByText('corr_demo_01HZX7K4')).toBeInTheDocument();
    expect(screen.getByText(/evt_demo_01.*evt_demo_06/i)).toBeInTheDocument();
  });

  it('replays the public fixture without submitting a scan request',()=>{
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/']}><App/></MemoryRouter></QueryClientProvider>);
    fireEvent.click(screen.getAllByRole('button',{name:/Inspect public scan replay/i})[0]);
    expect(screen.getAllByText('RUNNING').length).toBeGreaterThan(0);
    expect(screen.getByText(/EVENT evt_demo_01/i)).toBeInTheDocument();
    expect(screen.getAllByText('req_demo_01HZX7K4').length).toBeGreaterThan(0);
    expect(screen.getAllByText('corr_demo_01HZX7K4').length).toBeGreaterThan(0);
  });

});
