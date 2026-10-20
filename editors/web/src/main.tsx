import '@fontsource/atkinson-hyperlegible/400.css';
import '@fontsource/atkinson-hyperlegible/700.css';
import './styles.css';
import { render } from 'preact';
import { App } from './app.tsx';

render(<App />, document.getElementById('app')!);
